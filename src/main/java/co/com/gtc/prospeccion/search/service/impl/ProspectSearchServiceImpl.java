package co.com.gtc.prospeccion.search.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SearchRunStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.search.dto.request.RunSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchPreviewResponseDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchResultResponseDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchRunResponseDTO;
import co.com.gtc.prospeccion.search.entities.ApolloSeenET;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import co.com.gtc.prospeccion.search.mapper.SearchMapper;
import co.com.gtc.prospeccion.search.persistence.SearchRunDAO;
import co.com.gtc.prospeccion.search.rules.ProfileExclusions;
import co.com.gtc.prospeccion.search.rules.SearchFilterRules;
import co.com.gtc.prospeccion.search.service.ProspectSearchService;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Tarea 3.2: búsqueda y campañas. La ejecución de punta a punta está en {@link SearchPipeline}; aquí se validan los
 * filtros, se guarda la ejecución y se consulta el historial.
 */
@Service
public class ProspectSearchServiceImpl implements ProspectSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProspectSearchServiceImpl.class);

    static final int PREVIEW_SIZE = 25;

    private final SearchRunDAO searchRunDAO;
    private final SearchPipeline pipeline;
    private final SearchMapper mapper;
    private final TenantConfigRepository configRepository;
    private final MasterTableRepository masterTableRepository;
    private final EvidenceRepository evidenceRepository;
    private final ApolloClient apollo;
    private final DateTableResolver dateTableResolver;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public ProspectSearchServiceImpl(SearchRunDAO searchRunDAO, SearchPipeline pipeline, SearchMapper mapper,
            TenantConfigRepository configRepository, MasterTableRepository masterTableRepository,
            EvidenceRepository evidenceRepository, ApolloClient apollo, DateTableResolver dateTableResolver,
            CurrentUserProvider currentUserProvider, ObjectMapper objectMapper) {
        this.searchRunDAO = searchRunDAO;
        this.pipeline = pipeline;
        this.mapper = mapper;
        this.configRepository = configRepository;
        this.masterTableRepository = masterTableRepository;
        this.evidenceRepository = evidenceRepository;
        this.apollo = apollo;
        this.dateTableResolver = dateTableResolver;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchPreviewResponseDTO preview(RunSearchRequestDTO request) {
        SearchFiltersResponseDTO filters = validFilters(request.getFilters());
        TenantConfigET config = activeConfig();
        ApolloPeopleSearchRequest apolloRequest = SearchFilterRules.toApollo(filters,
                ProfileExclusions.from(config.getProfileOfInterest(), objectMapper).notToSearch());
        ApolloSearchPage page = apollo.searchPage(apolloRequest, 1, PREVIEW_SIZE);
        List<String> ids = page.people().stream().map(ApolloSearchPersonDTO::getId).filter(id -> id != null).toList();
        Set<String> seen = searchRunDAO.findSeen(ids);
        List<SearchPreviewResponseDTO.Person> sample = page.people().stream()
                .map(p -> new SearchPreviewResponseDTO.Person(p.getFirstName(), p.getLastNameObfuscated(),
                        p.getTitle(), p.getOrganization() == null ? null : p.getOrganization().getName(),
                        p.getHasEmail(), p.getId() != null && seen.contains(p.getId())))
                .toList();
        int fresh = (int) sample.stream().filter(p -> !p.alreadySeen()).count();
        return new SearchPreviewResponseDTO(page.totalEntries(), fresh, filters, sample);
    }

    @Override
    public SearchRunResponseDTO run(RunSearchRequestDTO request) {
        SearchFiltersResponseDTO filters = validFilters(request.getFilters());
        TenantConfigET config = activeConfig();
        UserET user = currentUserProvider.getCurrentUser();

        SearchRunET run = newRun(config, request.getNaturalQuery(), toJson(filters), request.getPmListContactId(),
                user);
        if (Boolean.TRUE.equals(request.getScheduled())) {
            run.setFlgScheduled(true);
            run.setScheduleFrequency(request.getFrequency() != null ? request.getFrequency() : SearchRunET.DAILY);
        }
        run = searchRunDAO.save(run);
        log.info("Búsqueda {} guardada{}; se ejecuta en segundo plano", run.getId(),
                run.getFlgScheduled() ? " como campaña " + run.getScheduleFrequency() : "");
        pipeline.executeAsync(run.getId(), user.getId());
        return getRun(run.getId());
    }

    @Override
    public Page<SearchRunResponseDTO> listRuns(Pageable pageable) {
        Page<SearchRunET> page = searchRunDAO.findAll(pageable);
        Map<Integer, Map<String, Long>> counts = searchRunDAO.countResults(
                page.getContent().stream().map(SearchRunET::getId).toList());
        return page.map(run -> withResults(mapper.toResponse(run), counts.get(run.getId())));
    }

    @Override
    public SearchRunResponseDTO getRun(Integer id) {
        SearchRunET run = find(id);
        return withResults(mapper.toResponse(run), searchRunDAO.countResults(List.of(id)).get(id));
    }

    @Override
    public List<SearchResultResponseDTO> runResults(Integer id) {
        find(id);
        List<ApolloSeenET> seen = searchRunDAO.findSeenByRun(id);
        List<Integer> contactIds = seen.stream().map(ApolloSeenET::getPmLeafletContactId).filter(c -> c != null)
                .toList();
        Map<Integer, Map<String, String>> evidence = new LinkedHashMap<>();
        if (!contactIds.isEmpty()) {
            for (EvidenceET e : evidenceRepository.findByPmLeafletContactIdIn(contactIds)) {
                evidence.computeIfAbsent(e.getPmLeafletContactId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(e.getField(), e.getValue());
            }
        }
        List<SearchResultResponseDTO> results = new ArrayList<>(seen.size());
        for (ApolloSeenET s : seen) {
            Map<String, String> data = s.getPmLeafletContactId() == null ? Map.of()
                    : evidence.getOrDefault(s.getPmLeafletContactId(), Map.of());
            results.add(new SearchResultResponseDTO(s.getResult(), s.getScore(), s.getPmLeafletContactId(),
                    data.get(EvidenceFields.FIRST_NAME), data.get(EvidenceFields.TITLE),
                    data.get(EvidenceFields.ORGANIZATION_NAME)));
        }
        return results;
    }

    @Override
    public SearchRunResponseDTO setScheduled(Integer id, boolean scheduled, String frequency) {
        SearchRunET run = find(id);
        if (run.getParentSearchRunId() != null) {
            throw new BusinessException(MessageKeys.VALIDATION_ERROR,
                    "La ejecución " + id + " es automática: la campaña es la búsqueda " + run.getParentSearchRunId());
        }
        run.setFlgScheduled(scheduled);
        if (frequency != null) {
            run.setScheduleFrequency(frequency);
        } else if (scheduled && run.getScheduleFrequency() == null) {
            run.setScheduleFrequency(SearchRunET.DAILY);
        }
        run.setLastUpdatedBy(currentUserProvider.getCurrentUser());
        run.setLastUpdatedDate(dateTableResolver.today());
        searchRunDAO.save(run);
        return getRun(id);
    }

    @Override
    public int rerunScheduledSearches(boolean force) {
        List<SearchRunET> campaigns = searchRunDAO.findScheduled();
        if (campaigns.isEmpty()) {
            return 0;
        }
        TenantConfigET config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc().orElse(null);
        if (config == null) {
            log.warn("Hay {} campaña(s) activa(s) pero el tenant no tiene configuración activa: no se ejecutan",
                    campaigns.size());
            return 0;
        }
        LocalDate today = dateTableResolver.today().getDate();
        int executed = 0;
        for (SearchRunET campaign : campaigns) {
            if (!force && !isDue(campaign, today)) {
                continue;
            }
            // Cada ejecución usa el perfil vigente y queda a nombre de quien creó la campaña.
            SearchRunET child = newRun(config, campaign.getNaturalQuery(), campaign.getFiltersJson(),
                    campaign.getPmListContactId(), campaign.getCreatedBy());
            child.setParentSearchRunId(campaign.getId());
            child = searchRunDAO.save(child);
            pipeline.execute(child.getId(), campaign.getCreatedBy().getId());
            executed++;
        }
        return executed;
    }

    /**
     * Diaria: si no ha corrido hoy. Semanal: si su última ejecución fue hace 7 días o más. Así el job puede correr varias
     * veces el mismo día (o reiniciarse) sin repetir una campaña.
     */
    boolean isDue(SearchRunET campaign, LocalDate today) {
        Optional<LocalDate> last = searchRunDAO.lastExecutionDate(campaign.getId());
        if (last.isEmpty()) {
            return true;
        }
        return SearchRunET.WEEKLY.equals(campaign.getScheduleFrequency())
                ? !last.get().isAfter(today.minusDays(7))
                : last.get().isBefore(today);
    }

    private SearchRunET newRun(TenantConfigET config, String naturalQuery, String filtersJson,
            Integer pmListContactId, UserET user) {
        SearchRunET run = new SearchRunET();
        run.setTenantConfigId(config.getId());
        run.setNaturalQuery(naturalQuery == null || naturalQuery.isBlank() ? null : naturalQuery.strip());
        run.setFiltersJson(filtersJson);
        run.setPmListContactId(pmListContactId);
        run.setFoundCount(0);
        run.setNewCount(0);
        run.setQualifiedCount(0);
        run.setFlgScheduled(false);
        run.setStatus(status(SearchRunStatus.PENDING));
        run.setCreatedBy(user);
        run.setCreatedDate(dateTableResolver.today());
        return run;
    }

    private SearchFiltersResponseDTO validFilters(SearchFiltersResponseDTO requested) {
        SearchFiltersResponseDTO filters = SearchFilterRules.clean(requested);
        filters.setModel(null);
        if (!SearchFilterRules.saysWhoToFind(filters)) {
            throw new BusinessException(MessageKeys.SEARCH_FILTERS_EMPTY,
                    "Los filtros no dicen a quién buscar (cargo, nivel, sector o palabra clave)");
        }
        return filters;
    }

    private TenantConfigET activeConfig() {
        return configRepository.findFirstByFlgActiveTrueOrderByIdDesc()
                .orElseThrow(() -> new BusinessException(MessageKeys.CONFIG_MISSING,
                        "El tenant no tiene configuración activa (perfil de interés, umbral y mensaje base)"));
    }

    private SearchRunET find(Integer id) {
        return searchRunDAO.findWithStatus(id)
                .orElseThrow(() -> new NotFoundException(MessageKeys.NOT_FOUND, "No existe la búsqueda " + id));
    }

    private MasterTableET status(String code) {
        return masterTableRepository.findFirstByTableCodeAndCode(SearchRunStatus.TABLECODE, code)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de búsqueda 600/" + code));
    }

    private String toJson(SearchFiltersResponseDTO filters) {
        try {
            return objectMapper.writeValueAsString(filters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudieron guardar los filtros", e);
        }
    }

    private static SearchRunResponseDTO withResults(SearchRunResponseDTO dto, Map<String, Long> counts) {
        dto.setResults(counts == null ? Map.of() : counts);
        return dto;
    }
}
