package co.com.gtc.prospeccion.draft.service.impl;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.common.util.HtmlSanitizerUtil;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.draft.dto.request.DiscardDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.DraftPreviewRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.UpdateDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.mapper.DraftMapper;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.draft.rules.DraftRules;
import co.com.gtc.prospeccion.draft.service.DraftService;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.GeminiException;
import co.com.gtc.prospeccion.integration.gemini.dto.DraftContact;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiDraftResult;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Borradores de correo. La redacción (tarea 4.3):
 * <ol>
 * <li>Toma el mensaje base y el perfil de la configuración vigente del tenant (6.2).</li>
 * <li>Arma lo que Gemini ve del contacto solo con su evidencia (la_evidence): nada sin fuente.</li>
 * <li>Limpia el HTML (HtmlSanitizerUtil) y lo revisa con {@link DraftRules}. Si tiene errores (marcas sin llenar,
 * sin firma...) pide otra redacción; si la segunda también falla, no guarda nada.</li>
 * <li>Guarda una versión nueva en la_draft, pendiente de revisión. Las anteriores quedan como historial.</li>
 * </ol>
 * No abre transacción: la llamada a Gemini tarda y no debe retener una conexión de la base.
 */
@Service
public class DraftServiceImpl implements DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftServiceImpl.class);

    /** Intentos de redacción cuando el borrador sale con errores (cada uno es una llamada a Gemini). */
    static final int MAX_ATTEMPTS = 2;

    private final DraftDAO draftDAO;
    private final DraftMapper draftMapper;
    private final TenantConfigRepository configRepository;
    private final EvidenceRepository evidenceRepository;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final GeminiClient gemini;
    private final ObjectMapper objectMapper;
    private final DraftSupport support;
    private final SuppressionService suppressionService;

    public DraftServiceImpl(DraftDAO draftDAO, DraftMapper draftMapper, TenantConfigRepository configRepository,
            EvidenceRepository evidenceRepository, MasterTableRepository masterTableRepository,
            DateTableResolver dateTableResolver, CurrentUserProvider currentUserProvider,
            UserRepository userRepository, GeminiClient gemini, ObjectMapper objectMapper, DraftSupport support,
            SuppressionService suppressionService) {
        this.support = support;
        this.suppressionService = suppressionService;
        this.draftDAO = draftDAO;
        this.draftMapper = draftMapper;
        this.configRepository = configRepository;
        this.evidenceRepository = evidenceRepository;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.gemini = gemini;
        this.objectMapper = objectMapper;
    }

    // ---------- Bandeja (5.1) ----------

    @Override
    @Transactional(readOnly = true)
    public Page<DraftResponseDTO> list(String statusCode, Pageable pageable) {
        Page<DraftET> page = draftDAO.findByStatus(statusCode, pageable);
        return new PageImpl<>(support.describe(page.getContent()), pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public DraftResponseDTO get(Integer id) {
        return support.describe(support.find(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DraftResponseDTO> versions(Integer pmLeafletContactId) {
        return support.describe(draftDAO.findVersions(pmLeafletContactId));
    }

    /**
     * La edición de una persona no cambia la fila: crea la versión siguiente (EDITED_BY_HUMAN) y la anterior queda
     * como historial. Solo sobre la última versión "Por revisar" y con la versión que la persona tenía abierta.
     */
    @Override
    @Transactional
    public DraftResponseDTO update(Integer id, UpdateDraftRequestDTO request) {
        DraftET current = support.find(id);
        support.requireLatestPending(current);
        if (!current.getVersion().equals(request.getVersion())) {
            throw new ConflictException(MessageKeys.CONFLICT, "El borrador " + id + " está en la versión "
                    + current.getVersion() + " y se editó la " + request.getVersion());
        }
        String subject = DraftRules.cleanSubject(request.getSubject());
        String html = HtmlSanitizerUtil.sanitize(request.getBody());
        String text = HtmlSanitizerUtil.toPlainText(html);
        if (subject.isEmpty() || text.isEmpty()) {
            throw new BusinessException(MessageKeys.VALIDATION_ERROR, "El asunto o el cuerpo quedaron vacíos");
        }
        // Las marcas sin llenar sí bloquean; lo demás (largo, firma...) queda como aviso para quien aprueba.
        if (DraftRules.check(subject, text, null, null).errors().contains(DraftRules.Issue.MARKS_LEFT)) {
            throw new BusinessException(MessageKeys.DRAFT_NOT_VALID, "Quedaron marcas {{...}} o [[...]] sin llenar");
        }
        if (subject.equals(current.getSubject()) && html.equals(current.getBody())) {
            return support.describe(current); // sin cambios: no se crea otra versión
        }

        DraftET next = new DraftET();
        next.setPmLeafletContactId(current.getPmLeafletContactId());
        next.setVersion(current.getVersion() + 1);
        next.setSubject(subject);
        next.setBody(html);
        next.setStatus(current.getStatus());
        next.setEditedByHuman(true);
        next.setModel(current.getModel());
        next.setPromptVersion(current.getPromptVersion());
        next.setCreatedBy(currentUserProvider.getCurrentUser());
        next.setCreatedDate(dateTableResolver.today());
        DraftET saved;
        try {
            saved = draftDAO.save(next);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(MessageKeys.CONFLICT, "Otra persona guardó una versión del borrador " + id);
        }
        log.info("Borrador del contacto {}: versión {} editada por una persona", saved.getPmLeafletContactId(),
                saved.getVersion());
        return support.describe(saved);
    }

    @Override
    @Transactional
    public DraftResponseDTO discard(Integer id, DiscardDraftRequestDTO request) {
        DraftET draft = support.find(id);
        support.requireLatestPending(draft);
        draft.setStatus(support.status(DraftStatus.DISCARDED));
        draft.setDiscardReason(request.getReason().strip());
        draft.setLastUpdatedBy(currentUserProvider.getCurrentUser());
        draft.setLastUpdatedDate(dateTableResolver.today());
        DraftET saved = draftDAO.save(draft);
        log.info("Borrador {} (versión {}) descartado", saved.getId(), saved.getVersion());
        return support.describe(saved);
    }

    // ---------- Redacción (4.3) ----------

    @Override
    public DraftResponseDTO generate(Integer pmLeafletContactId, Integer userId) {
        TenantConfigET config = activeConfig()
                .filter(c -> c.getBaseMessage() != null && !c.getBaseMessage().isBlank())
                .orElseThrow(DraftServiceImpl::baseMessageMissing);
        List<EvidenceET> evidence = evidenceRepository.findByPmLeafletContactId(pmLeafletContactId);
        if (evidence.isEmpty()) {
            throw new NotFoundException(MessageKeys.NOT_FOUND,
                    "El contacto " + pmLeafletContactId + " no tiene evidencia para redactar");
        }
        String email = emailOf(evidence);
        if (email != null && suppressionService.isSuppressed(email)) {
            throw new BusinessException(MessageKeys.CONTACT_SUPPRESSED,
                    "El contacto " + pmLeafletContactId + " se dio de baja: no se redacta");
        }
        if (draftDAO.hasApprovedVersion(pmLeafletContactId)) {
            throw new ConflictException(MessageKeys.DRAFT_ALREADY_APPROVED,
                    "El contacto " + pmLeafletContactId + " ya tiene un borrador aprobado");
        }
        MasterTableET pending = masterTableRepository
                .findFirstByTableCodeAndCode(DraftStatus.TABLECODE, DraftStatus.PENDING_REVIEW)
                .orElseThrow(() -> new IllegalStateException(
                        "Falta el estado de borrador 601/1 en sy_mastertable (V2__catalogos_y_etiquetas.sql)"));
        UserET author = userId != null ? userRepository.getReferenceById(userId) : currentUserProvider.getCurrentUser();

        Written written = write(config.getBaseMessage(), config.getProfileOfInterest(), toContact(evidence));

        int version = draftDAO.findLatestByContact(pmLeafletContactId).map(d -> d.getVersion() + 1).orElse(1);
        DraftET draft = new DraftET();
        draft.setPmLeafletContactId(pmLeafletContactId);
        draft.setVersion(version);
        draft.setSubject(written.subject());
        draft.setBody(written.html());
        draft.setStatus(pending);
        draft.setEditedByHuman(false);
        draft.setModel(written.model());
        draft.setPromptVersion(written.promptVersion());
        draft.setCreatedBy(author);
        draft.setCreatedDate(dateTableResolver.today());
        DraftET saved;
        try {
            saved = draftDAO.save(draft);
        } catch (DataIntegrityViolationException e) {
            // Otra redacción del mismo contacto guardó la misma versión al tiempo (UK_LA_DRAFT_IDX01).
            throw new ConflictException(MessageKeys.CONFLICT,
                    "Se redactó otra versión del contacto " + pmLeafletContactId + " al mismo tiempo");
        }
        log.info("Borrador v{} del contacto {} redactado ({} palabras, avisos: {})", version, pmLeafletContactId,
                written.check().words(), written.check().warnings());

        DraftResponseDTO response = draftMapper.toResponse(saved);
        response.setStatusCode(DraftStatus.PENDING_REVIEW);
        fillCheck(response, written);
        return response;
    }

    @Override
    public DraftResponseDTO preview(DraftPreviewRequestDTO request) {
        Optional<TenantConfigET> config = activeConfig();
        String baseMessage = request.getBaseMessage() != null && !request.getBaseMessage().isBlank()
                ? request.getBaseMessage()
                : config.map(TenantConfigET::getBaseMessage).filter(m -> !m.isBlank())
                        .orElseThrow(DraftServiceImpl::baseMessageMissing);
        DraftPreviewRequestDTO.Contact c = request.getContact();
        DraftContact contact = new DraftContact(blankToNull(c.getFirstName()), blankToNull(c.getTitle()),
                blankToNull(c.getOrganizationName()), blankToNull(c.getIndustry()), blankToNull(c.getCompanySize()),
                blankToNull(c.getCity()));

        Written written = write(baseMessage, config.map(TenantConfigET::getProfileOfInterest).orElse(null), contact);
        log.info("Prueba de redacción: {} palabras, avisos: {}", written.check().words(), written.check().warnings());

        DraftResponseDTO response = new DraftResponseDTO();
        response.setSubject(written.subject());
        response.setBody(written.html());
        response.setEditedByHuman(false);
        response.setModel(written.model());
        response.setPromptVersion(written.promptVersion());
        fillCheck(response, written);
        return response;
    }

    @Override
    public int expireOldDrafts() {
        // TODO: regla de vencimiento por definir.
        throw new UnsupportedOperationException("TODO");
    }

    /** Borrador ya limpio y revisado. */
    record Written(String subject, String html, String text, DraftRules.Check check, String model,
            String promptVersion) {
    }

    /** Pide el borrador a Gemini, lo limpia y lo revisa; si tiene errores, pide otro (hasta {@link #MAX_ATTEMPTS}). */
    Written write(String baseMessage, String profileOfInterest, DraftContact contact) {
        String knownData = knownData(profileOfInterest, contact);
        String lastProblem = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GeminiDraftResult raw;
            try {
                raw = gemini.draftEmail(baseMessage, profileOfInterest, contact);
            } catch (GeminiException.InvalidResponse e) {
                lastProblem = "respuesta inválida";
                log.warn("Gemini respondió un borrador inválido (intento {} de {})", attempt, MAX_ATTEMPTS);
                continue;
            }
            String subject = DraftRules.cleanSubject(raw.getSubject());
            String html = HtmlSanitizerUtil.sanitize(raw.getBodyHtml());
            String text = HtmlSanitizerUtil.toPlainText(html);
            DraftRules.Check check = DraftRules.check(subject, text, baseMessage, knownData);
            if (check.isValid()) {
                return new Written(subject, html, text, check, raw.getModel(), raw.getPromptVersion());
            }
            lastProblem = check.errors().toString();
            log.warn("El borrador de Gemini no cumple las reglas (intento {} de {}): {}", attempt, MAX_ATTEMPTS,
                    check.errors());
        }
        throw new BusinessException(MessageKeys.DRAFT_NOT_VALID,
                "Gemini no logró un borrador válido en " + MAX_ATTEMPTS + " intentos: " + lastProblem);
    }

    /**
     * Datos del contacto a partir de su evidencia. Si un campo tiene varias fuentes, gana Apollo. La ciudad es la de
     * la persona o, si falta, la de la empresa.
     */
    public static DraftContact toContact(List<EvidenceET> evidence) {
        Map<String, String> values = new LinkedHashMap<>();
        evidence.stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .sorted(Comparator.comparing(e -> !EvidenceFields.SOURCE_APOLLO.equals(e.getSource())))
                .forEach(e -> values.putIfAbsent(e.getField(), e.getValue().strip()));
        String city = values.getOrDefault(EvidenceFields.CITY, values.get(EvidenceFields.ORGANIZATION_CITY));
        return new DraftContact(
                values.get(EvidenceFields.FIRST_NAME), // first_name de Apollo: sin apellidos
                values.get(EvidenceFields.TITLE),
                values.get(EvidenceFields.ORGANIZATION_NAME),
                values.get(EvidenceFields.INDUSTRY),
                values.get(EvidenceFields.COMPANY_SIZE),
                city);
    }

    /**
     * Correo de trabajo del contacto según su evidencia (EvidenceFields.EMAIL), en minúsculas, o null. Si hay varios,
     * gana el de Apollo. Gemini nunca lo recibe.
     */
    public static String emailOf(List<EvidenceET> evidence) {
        return evidence.stream()
                .filter(e -> EvidenceFields.EMAIL.equals(e.getField()) && e.getValue() != null && !e.getValue().isBlank())
                .sorted(Comparator.comparing(e -> !EvidenceFields.SOURCE_APOLLO.equals(e.getSource())))
                .map(e -> e.getValue().strip().toLowerCase(java.util.Locale.ROOT))
                .findFirst()
                .orElse(null);
    }

    /** Lo que Gemini recibe además del mensaje base: así DraftRules sabe qué números vienen en los datos. */
    private String knownData(String profileOfInterest, DraftContact contact) {
        StringBuilder data = new StringBuilder();
        try {
            data.append(objectMapper.writeValueAsString(contact));
            if (profileOfInterest != null && !profileOfInterest.isBlank()) {
                JsonNode company = objectMapper.readTree(profileOfInterest).path("company");
                if (!company.isMissingNode()) {
                    data.append('\n').append(company);
                }
            }
        } catch (JsonProcessingException e) {
            // Perfil dañado: GeminiClient tampoco lo envía, así que no aporta datos.
        }
        return data.toString();
    }

    private static void fillCheck(DraftResponseDTO response, Written written) {
        response.setBodyText(written.text());
        response.setWords(written.check().words());
        response.setWarnings(written.check().warnings().stream().map(Enum::name).toList());
    }

    private Optional<TenantConfigET> activeConfig() {
        return configRepository.findFirstByFlgActiveTrueOrderByIdDesc();
    }

    private static BusinessException baseMessageMissing() {
        return new BusinessException(MessageKeys.BASE_MESSAGE_MISSING,
                "El tenant no tiene mensaje base en la configuración vigente");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
