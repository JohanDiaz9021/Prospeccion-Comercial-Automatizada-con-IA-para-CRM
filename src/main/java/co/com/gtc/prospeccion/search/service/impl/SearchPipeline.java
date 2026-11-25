package co.com.gtc.prospeccion.search.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SearchRunStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.SearchExecutorConfig;
import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService.Reason;
import co.com.gtc.prospeccion.draft.service.DraftService;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.ApolloException;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.service.EmailDiscoveryService;
import co.com.gtc.prospeccion.prospect.service.ProspectCreationService;
import co.com.gtc.prospeccion.prospect.service.QualificationService;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;
import co.com.gtc.prospeccion.search.entities.ApolloSeenET;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import co.com.gtc.prospeccion.search.persistence.SearchRunDAO;
import co.com.gtc.prospeccion.search.rules.ProfileExclusions;
import co.com.gtc.prospeccion.search.rules.SearchFilterRules;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Ejecución de una búsqueda (tarea 3.2), de punta a punta y sin intervención humana:
 * <ol>
 * <li><b>Apollo</b> (0 créditos): recorre páginas hasta juntar {@code prospeccion.search.max-new-per-run} personas que
 * nunca se habían visto (la_apolloseen). Competidores y empresas excluidas a mano ni se buscan.</li>
 * <li><b>Calificación</b> con Gemini (3.5): las que no alcanzan el umbral quedan DESCARTADO_IA y no gastan
 * créditos.</li>
 * <li><b>Correo</b> (4.1, gasta créditos con los topes de la 4.2).</li>
 * <li><b>Exclusiones</b> con el correo: bajas, colaboradores, competidores y empresas o contactos ya prospectados.</li>
 * <li><b>Contacto</b> en el CRM con su evidencia y <b>borrador</b> (4.3) en la bandeja (5.1). El único paso humano es
 * aprobar el correo.</li>
 * </ol>
 * Cada persona procesada queda en la_apolloseen para no pagar dos veces por ella. Si se acaba un tope de créditos o el
 * cupo de Apollo, la ejecución termina con lo que alcanzó y las personas que faltaron se toman en la siguiente.
 * <p>
 * No abre una transacción larga: Apollo y Gemini tardan. Cada contacto se crea en su propia transacción.
 */
@Component
public class SearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(SearchPipeline.class);

    static final int MAX_DETAIL = 1000;

    private final SearchRunDAO searchRunDAO;
    private final TenantConfigRepository configRepository;
    private final MasterTableRepository masterTableRepository;
    private final UserRepository userRepository;
    private final DateTableResolver dateTableResolver;
    private final ApolloClient apollo;
    private final QualificationService qualificationService;
    private final EmailDiscoveryService emailDiscoveryService;
    private final CrmExclusionService exclusionService;
    private final ProspectCreationService prospectCreationService;
    private final DraftService draftService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ProspeccionProperties properties;
    private final Executor executor;

    public SearchPipeline(SearchRunDAO searchRunDAO, TenantConfigRepository configRepository,
            MasterTableRepository masterTableRepository, UserRepository userRepository,
            DateTableResolver dateTableResolver, ApolloClient apollo, QualificationService qualificationService,
            EmailDiscoveryService emailDiscoveryService, CrmExclusionService exclusionService,
            ProspectCreationService prospectCreationService, DraftService draftService,
            TransactionTemplate transactionTemplate, ObjectMapper objectMapper, ProspeccionProperties properties,
            @Qualifier(SearchExecutorConfig.SEARCH_EXECUTOR) Executor executor) {
        this.searchRunDAO = searchRunDAO;
        this.configRepository = configRepository;
        this.masterTableRepository = masterTableRepository;
        this.userRepository = userRepository;
        this.dateTableResolver = dateTableResolver;
        this.apollo = apollo;
        this.qualificationService = qualificationService;
        this.emailDiscoveryService = emailDiscoveryService;
        this.exclusionService = exclusionService;
        this.prospectCreationService = prospectCreationService;
        this.draftService = draftService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executor = executor;
    }

    /** Lo que pasó con las personas nuevas de una ejecución. */
    public record Summary(int found, int fresh, int qualified, int created, int drafted, String stopReason) {
    }

    /** Personas nuevas de Apollo para unos filtros. */
    record Collected(int totalEntries, List<ApolloSearchPersonDTO> people) {
    }

    /**
     * Ejecuta en segundo plano, en el tenant de la petición. La ejecución ya debe estar guardada como Pendiente; quien
     * la lanzó consulta su estado con GET /api/search/runs/{id}.
     */
    public void executeAsync(Integer searchRunId, Integer userId) {
        String tenant = TenantContext.getTenant();
        try {
            executor.execute(() -> {
                try {
                    TenantContext.setTenant(tenant);
                    execute(searchRunId, userId);
                } finally {
                    TenantContext.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            searchRunDAO.findWithStatus(searchRunId).ifPresent(run -> finish(run, SearchRunStatus.FAILED,
                    "Hay demasiadas búsquedas en curso. Intente de nuevo en unos minutos."));
        }
    }

    /** Ejecuta en este hilo (el job ya fijó el tenant). Nunca lanza: los errores quedan en la ejecución. */
    public Summary execute(Integer searchRunId, Integer userId) {
        SearchRunET run = searchRunDAO.findWithStatus(searchRunId)
                .orElseThrow(() -> new IllegalStateException("No existe la búsqueda " + searchRunId));
        run.setStatus(status(SearchRunStatus.RUNNING));
        run = searchRunDAO.save(run);
        try {
            Summary summary = process(run, userId);
            finish(run, SearchRunStatus.FINISHED, summary.stopReason());
            log.info("Búsqueda {}: {} en Apollo, {} nuevas, {} calificadas, {} contactos, {} borradores{}",
                    searchRunId, summary.found(), summary.fresh(), summary.qualified(), summary.created(),
                    summary.drafted(), summary.stopReason() == null ? "" : " (se detuvo antes)");
            return summary;
        } catch (RuntimeException e) {
            log.error("La búsqueda {} falló", searchRunId, e);
            finish(run, SearchRunStatus.FAILED, failureDetail(e));
            return new Summary(run.getFoundCount(), run.getNewCount(), run.getQualifiedCount(), 0, 0,
                    failureDetail(e));
        }
    }

    private Summary process(SearchRunET run, Integer userId) {
        TenantConfigET config = configRepository.findById(run.getTenantConfigId())
                .orElseThrow(() -> new IllegalStateException("No existe la configuración " + run.getTenantConfigId()));
        ProfileExclusions lists = ProfileExclusions.from(config.getProfileOfInterest(), objectMapper);
        ApolloPeopleSearchRequest request = SearchFilterRules.toApollo(SearchFilterRules.clean(readFilters(run)),
                lists.notToSearch());

        Collected collected = collectNew(request);
        run.setFoundCount(collected.totalEntries());
        run.setNewCount(collected.people().size());
        searchRunDAO.save(run);
        if (collected.people().isEmpty()) {
            return new Summary(collected.totalEntries(), 0, 0, 0, 0, null);
        }

        List<GeminiQualificationResult> scores = qualificationService.qualify(collected.people(), request);
        CreditContext credits = CreditContext.forSearchRun(run.getId(), userId);
        int qualified = 0;
        int created = 0;
        int drafted = 0;
        int unscored = 0;
        List<String> draftErrors = new ArrayList<>();
        String stopReason = null;

        for (int i = 0; i < collected.people().size(); i++) {
            ApolloSearchPersonDTO person = collected.people().get(i);
            GeminiQualificationResult score = i < scores.size() ? scores.get(i) : null;
            if (score == null || score.getError() != null) {
                unscored++; // No queda como vista: se vuelve a intentar en la siguiente ejecución.
                continue;
            }
            if (!Boolean.TRUE.equals(score.getQualified())) {
                markSeen(person.getId(), ApolloSeenET.RESULT_DISCARDED_AI, score.getScore(), run.getId(), null, userId);
                continue;
            }
            qualified++;

            EmailDiscoveryResult found;
            try {
                found = emailDiscoveryService.discover(person.getId(), credits);
            } catch (CreditLimitExceededException e) {
                stopReason = "Se alcanzó el tope de créditos de %s (%s): se detuvo aquí y las personas que faltaron se toman en la siguiente ejecución."
                        .formatted(e.getProvider(), e.getScope() == CreditLimitExceededException.Scope.MONTHLY
                                ? "del mes" : "por búsqueda");
                qualified--;
                break;
            } catch (ApolloException.RateLimited e) {
                stopReason = "Se agotó el cupo de Apollo por ahora: las personas que faltaron se toman en la siguiente ejecución.";
                qualified--;
                break;
            }

            if (found.getOutcome() != EmailDiscoveryResult.Outcome.FOUND) {
                String result = found.getOutcome() == EmailDiscoveryResult.Outcome.OPTED_OUT
                        ? ApolloSeenET.RESULT_EXCLUDED : ApolloSeenET.RESULT_NO_EMAIL;
                markSeen(person.getId(), result, score.getScore(), run.getId(), null, userId);
                continue;
            }
            EmailDiscoveryResult accepted = found;
            String companyDomain = found.getPerson() == null || found.getPerson().getOrganization() == null ? null
                    : found.getPerson().getOrganization().getPrimaryDomain();
            Optional<Reason> excluded = exclusionService.exclusionReason(found.getEmail(), companyDomain, lists);
            if (excluded.isPresent()) {
                log.debug("Búsqueda {}: una persona excluida ({})", run.getId(), excluded.get());
                markSeen(person.getId(), ApolloSeenET.RESULT_EXCLUDED, score.getScore(), run.getId(), null, userId);
                continue;
            }

            Integer contactId = transactionTemplate.execute(tx -> {
                Integer id = prospectCreationService.createProspect(accepted, score, run.getId(),
                        run.getPmListContactId(), userId);
                markSeen(person.getId(), ApolloSeenET.RESULT_CREATED, score.getScore(), run.getId(), id, userId);
                return id;
            });
            created++;
            try {
                draftService.generate(contactId, userId);
                drafted++;
            } catch (RuntimeException e) {
                // El contacto ya existe: el borrador se puede volver a pedir desde la bandeja.
                log.warn("Búsqueda {}: el contacto {} quedó sin borrador ({})", run.getId(), contactId,
                        e.getClass().getSimpleName());
                draftErrors.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        run.setQualifiedCount(qualified);
        List<String> notes = new ArrayList<>();
        if (stopReason != null) {
            notes.add(stopReason);
        }
        if (unscored > 0) {
            notes.add(unscored + " persona(s) no se pudieron calificar: se intentan de nuevo en la siguiente ejecución.");
        }
        if (!draftErrors.isEmpty()) {
            notes.add(draftErrors.size() + " contacto(s) quedaron sin borrador: " + draftErrors.get(0));
        }
        return new Summary(collected.totalEntries(), collected.people().size(), qualified, created, drafted,
                notes.isEmpty() ? null : String.join(" ", notes));
    }

    /**
     * Páginas de Apollo hasta juntar las personas nuevas que caben en una ejecución. Gratis: solo cuenta para los
     * límites de tasa de Apollo.
     */
    Collected collectNew(ApolloPeopleSearchRequest request) {
        int max = Math.max(1, properties.getSearch().getMaxNewPerRun());
        int maxPages = Math.max(1, Math.min(properties.getSearch().getMaxPages(), ApolloClient.MAX_PAGE));
        List<ApolloSearchPersonDTO> fresh = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int total = 0;
        for (int page = 1; page <= maxPages && fresh.size() < max; page++) {
            ApolloSearchPage result = apollo.searchPage(request, page, ApolloClient.MAX_PER_PAGE);
            total = result.totalEntries();
            List<String> pageIds = result.people().stream().map(ApolloSearchPersonDTO::getId)
                    .filter(id -> id != null && !id.isBlank()).toList();
            Set<String> seen = searchRunDAO.findSeen(pageIds);
            for (ApolloSearchPersonDTO person : result.people()) {
                String id = person.getId();
                if (id != null && !id.isBlank() && !seen.contains(id) && ids.add(id)) {
                    fresh.add(person);
                    if (fresh.size() == max) {
                        break;
                    }
                }
            }
            if (!result.hasMore()) {
                break;
            }
        }
        return new Collected(total, fresh);
    }

    private void markSeen(String providerId, String result, Integer score, Integer searchRunId, Integer contactId,
            Integer userId) {
        ApolloSeenET seen = new ApolloSeenET();
        seen.setProviderId(providerId);
        seen.setResult(result);
        seen.setScore(score);
        seen.setSearchRunId(searchRunId);
        seen.setPmLeafletContactId(contactId);
        seen.setCreatedBy(userRepository.getReferenceById(userId));
        seen.setCreatedDate(dateTableResolver.today());
        try {
            searchRunDAO.saveSeen(seen);
        } catch (DataIntegrityViolationException e) {
            // Otra búsqueda la procesó al mismo tiempo: ya está en la memoria.
            log.debug("Persona de Apollo ya registrada por otra búsqueda");
        }
    }

    private SearchFiltersResponseDTO readFilters(SearchRunET run) {
        try {
            return objectMapper.readValue(run.getFiltersJson(), SearchFiltersResponseDTO.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Los filtros guardados de la búsqueda " + run.getId() + " no son JSON", e);
        }
    }

    private void finish(SearchRunET run, String statusCode, String detail) {
        run.setStatus(status(statusCode));
        run.setErrorDetail(cut(detail));
        run.setLastUpdatedDate(dateTableResolver.today());
        searchRunDAO.save(run);
    }

    private MasterTableET status(String code) {
        return masterTableRepository.findFirstByTableCodeAndCode(SearchRunStatus.TABLECODE, code)
                .orElseThrow(() -> new IllegalStateException(
                        "Falta el estado de búsqueda 600/" + code + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)"));
    }

    private static String failureDetail(RuntimeException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                : e.getMessage();
        return "No se pudo completar la búsqueda. " + message;
    }

    private static String cut(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_DETAIL ? value.substring(0, MAX_DETAIL) : value;
    }
}
