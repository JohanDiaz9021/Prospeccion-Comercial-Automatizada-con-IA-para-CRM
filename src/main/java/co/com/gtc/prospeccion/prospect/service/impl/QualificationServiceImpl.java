package co.com.gtc.prospeccion.prospect.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.GeminiException;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.integration.gemini.dto.QualificationCandidate;
import co.com.gtc.prospeccion.prospect.dto.request.QualificationPreviewRequest;
import co.com.gtc.prospeccion.prospect.scoring.ScoringRules;
import co.com.gtc.prospeccion.prospect.service.QualificationService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tenantconfig.service.TenantConfigService;

/**
 * Calificación con Gemini (tarea 3.5).
 * <ol>
 * <li>Toma el perfil de interés y el umbral de la configuración vigente del tenant (6.2).</li>
 * <li>Arma lo que Gemini ve de cada persona: cargo, empresa y los filtros de la búsqueda (sin nombre ni IDs).</li>
 * <li>Envía lotes de {@code integrations.gemini.qualification-batch-size} contactos por llamada.</li>
 * <li>Normaliza con {@link ScoringRules}: el puntaje y la categoría los calcula el código, no la IA.</li>
 * </ol>
 * Si Gemini bloquea o responde mal un lote, se reintenta contacto por contacto para que uno raro no tumbe a los
 * demás; el que siga fallando queda con {@code error} y sin puntaje. Las fallas de llave, cuota o red se propagan:
 * la búsqueda completa queda en error y se puede reintentar.
 * <p>
 * No abre transacción: las llamadas a Gemini tardan y no deben retener una conexión de la base.
 */
@Service
public class QualificationServiceImpl implements QualificationService {

    private static final Logger log = LoggerFactory.getLogger(QualificationServiceImpl.class);

    static final String ERROR_BLOCKED = "BLOCKED";
    static final String ERROR_INVALID_RESPONSE = "INVALID_RESPONSE";

    private final TenantConfigRepository configRepository;
    private final GeminiClient gemini;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public QualificationServiceImpl(TenantConfigRepository configRepository, GeminiClient gemini,
            ObjectMapper objectMapper, IntegrationsProperties integrations) {
        this.configRepository = configRepository;
        this.gemini = gemini;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, integrations.getGemini().getQualificationBatchSize());
    }

    @Override
    public List<GeminiQualificationResult> qualify(List<ApolloSearchPersonDTO> people,
            ApolloPeopleSearchRequest filters) {
        if (people == null || people.isEmpty()) {
            return List.of();
        }
        TenantConfigET config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc()
                .filter(c -> c.getProfileOfInterest() != null && !c.getProfileOfInterest().isBlank())
                .orElseThrow(QualificationServiceImpl::profileMissing);

        List<QualificationCandidate> candidates = new ArrayList<>(people.size());
        for (int i = 0; i < people.size(); i++) {
            candidates.add(toCandidate(String.valueOf(i + 1), people.get(i), filters));
        }
        return run(config.getProfileOfInterest(), thresholdOf(null, config), candidates,
                "configuración " + config.getId());
    }

    @Override
    public List<GeminiQualificationResult> preview(QualificationPreviewRequest request) {
        Optional<TenantConfigET> config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc();
        boolean ownProfile = request.getProfileOfInterest() != null && !request.getProfileOfInterest().isBlank();
        String profile = ownProfile
                ? request.getProfileOfInterest()
                : config.map(TenantConfigET::getProfileOfInterest).filter(p -> !p.isBlank())
                        .orElseThrow(QualificationServiceImpl::profileMissing);

        List<QualificationPreviewRequest.Contact> contacts = request.getContacts();
        List<QualificationCandidate> candidates = new ArrayList<>(contacts.size());
        for (int i = 0; i < contacts.size(); i++) {
            QualificationPreviewRequest.Contact c = contacts.get(i);
            candidates.add(new QualificationCandidate(String.valueOf(i + 1), blankToNull(c.getTitle()),
                    blankToNull(c.getOrganizationName()), clean(c.getSeniorities()), clean(c.getPersonLocations()),
                    clean(c.getOrganizationLocations()), clean(c.getIndustries()), clean(c.getEmployeeRanges()),
                    blankToNull(c.getKeywords()), clean(c.getSignals())));
        }
        return run(profile, thresholdOf(request.getScoreThreshold(), config.orElse(null)), candidates,
                ownProfile ? "prueba con perfil sin guardar" : "prueba con la configuración vigente");
    }

    private List<GeminiQualificationResult> run(String profile, int threshold, List<QualificationCandidate> candidates,
            String context) {
        ScoringRules rules = ScoringRules.fromProfile(readProfile(profile));
        List<GeminiQualificationResult> results = new ArrayList<>(candidates.size());
        for (int from = 0; from < candidates.size(); from += batchSize) {
            List<QualificationCandidate> batch = candidates.subList(from, Math.min(from + batchSize, candidates.size()));
            for (GeminiQualificationResult raw : qualifyBatch(profile, batch)) {
                results.add(raw.getError() != null ? raw : rules.apply(raw, threshold));
            }
        }

        long qualified = results.stream().filter(r -> Boolean.TRUE.equals(r.getQualified())).count();
        long errors = results.stream().filter(r -> r.getError() != null).count();
        log.info("Calificación ({}, umbral {}): {} contactos, {} pasan, {} sin calificar",
                context, threshold, results.size(), qualified, errors);
        return results;
    }

    private static int thresholdOf(Integer requested, TenantConfigET config) {
        if (requested != null) {
            return requested;
        }
        return config != null && config.getScoreThreshold() != null
                ? config.getScoreThreshold()
                : TenantConfigService.DEFAULT_SCORE_THRESHOLD;
    }

    private static BusinessException profileMissing() {
        return new BusinessException(MessageKeys.PROFILE_MISSING,
                "El tenant no tiene perfil de interés en la configuración vigente");
    }

    /** Quita vacíos y espacios; null si no queda nada (así no viaja a Gemini). */
    private static List<String> clean(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> cleaned = values.stream().map(QualificationServiceImpl::blankToNull).filter(v -> v != null)
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Un lote; si Gemini lo bloquea, lo responde mal u omite contactos, esos se reintentan de a uno. */
    private List<GeminiQualificationResult> qualifyBatch(String profile, List<QualificationCandidate> batch) {
        List<GeminiQualificationResult> raw;
        try {
            raw = gemini.qualifyContacts(profile, batch);
        } catch (GeminiException.Blocked | GeminiException.InvalidResponse e) {
            if (batch.size() == 1) {
                log.warn("Gemini no calificó un contacto: {}", e.getClass().getSimpleName());
                return List.of(failed(e instanceof GeminiException.Blocked ? ERROR_BLOCKED : ERROR_INVALID_RESPONSE));
            }
            log.warn("Gemini no calificó un lote de {} ({}); se reintenta uno por uno", batch.size(),
                    e.getClass().getSimpleName());
            return oneByOne(profile, batch);
        }

        if (raw.size() != batch.size()) {
            return batch.size() == 1 ? List.of(failed(ERROR_INVALID_RESPONSE)) : oneByOne(profile, batch);
        }
        List<GeminiQualificationResult> complete = new ArrayList<>(raw);
        for (int i = 0; i < complete.size(); i++) {
            if (complete.get(i) == null) {
                complete.set(i, batch.size() == 1
                        ? failed(ERROR_INVALID_RESPONSE)
                        : qualifyBatch(profile, List.of(batch.get(i))).get(0));
            }
        }
        return complete;
    }

    private List<GeminiQualificationResult> oneByOne(String profile, List<QualificationCandidate> batch) {
        return batch.stream().map(candidate -> qualifyBatch(profile, List.of(candidate)).get(0)).toList();
    }

    private static GeminiQualificationResult failed(String error) {
        return GeminiQualificationResult.builder().qualified(false).error(error).build();
    }

    static QualificationCandidate toCandidate(String ref, ApolloSearchPersonDTO person,
            ApolloPeopleSearchRequest filters) {
        ApolloPeopleSearchRequest f = filters != null ? filters : new ApolloPeopleSearchRequest();
        String organization = person.getOrganization() == null ? null : person.getOrganization().getName();
        return new QualificationCandidate(
                ref,
                blankToNull(person.getTitle()),
                blankToNull(organization),
                f.getPersonSeniorities(),
                f.getPersonLocations(),
                f.getOrganizationLocations(),
                f.getOrganizationIndustries(),
                f.getOrganizationNumEmployeesRanges(),
                blankToNull(f.getQKeywords()),
                null); // TODO 4.1: tecnologías y antigüedad en el cargo cuando se confirmen los campos de Apollo
    }

    private JsonNode readProfile(String profile) {
        try {
            JsonNode json = objectMapper.readTree(profile);
            if (json != null && json.isObject()) {
                return json;
            }
        } catch (JsonProcessingException e) {
            // se reporta abajo
        }
        // 6.2 valida el JSON al guardar; esto solo pasa si alguien editó la base a mano.
        throw new BusinessException(MessageKeys.PROFILE_INVALID, "El perfil de interés vigente no es un objeto JSON");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
