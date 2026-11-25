package co.com.gtc.prospeccion.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SearchRunStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService.Reason;
import co.com.gtc.prospeccion.draft.service.DraftService;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.ApolloException;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage.ApolloRateLimit;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloOrganizationDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult.Outcome;
import co.com.gtc.prospeccion.prospect.service.EmailDiscoveryService;
import co.com.gtc.prospeccion.prospect.service.ProspectCreationService;
import co.com.gtc.prospeccion.prospect.service.QualificationService;
import co.com.gtc.prospeccion.search.entities.ApolloSeenET;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import co.com.gtc.prospeccion.search.persistence.SearchRunDAO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/** La búsqueda de punta a punta (3.2), con Apollo, Gemini, correo y CRM simulados. */
class SearchPipelineTest {

    static final int USER = 5;
    static final String FILTERS = "{\"personTitles\": [\"Gerente de TI\"], \"organizationLocations\": [\"Bogota, Colombia\"]}";

    private final SearchRunDAO dao = mock(SearchRunDAO.class);
    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final MasterTableRepository masters = mock(MasterTableRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DateTableResolver dates = mock(DateTableResolver.class);
    private final ApolloClient apollo = mock(ApolloClient.class);
    private final QualificationService qualification = mock(QualificationService.class);
    private final EmailDiscoveryService emails = mock(EmailDiscoveryService.class);
    private final CrmExclusionService exclusions = mock(CrmExclusionService.class);
    private final ProspectCreationService creation = mock(ProspectCreationService.class);
    private final DraftService drafts = mock(DraftService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final List<ApolloSeenET> seen = new ArrayList<>();
    private SearchRunET run;
    private SearchPipeline pipeline;

    @BeforeEach
    void preparar() {
        run = new SearchRunET();
        run.setId(7);
        run.setTenantConfigId(3);
        run.setFiltersJson(FILTERS);
        run.setFoundCount(0);
        run.setNewCount(0);
        run.setQualifiedCount(0);
        when(dao.findWithStatus(7)).thenReturn(Optional.of(run));
        when(dao.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dao.saveSeen(any())).thenAnswer(i -> {
            seen.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(dao.findSeen(anyCollection())).thenReturn(Set.of());
        TenantConfigET config = new TenantConfigET();
        config.setId(3);
        config.setProfileOfInterest("{\"exclusions\": {\"competitorDomains\": [\"competidor.com\"]}}");
        when(configs.findById(3)).thenReturn(Optional.of(config));
        for (String code : List.of("1", "2", "3", "4")) {
            MasterTableET status = new MasterTableET();
            ReflectionTestUtils.setField(status, "tableCode", SearchRunStatus.TABLECODE);
            ReflectionTestUtils.setField(status, "code", code);
            when(masters.findFirstByTableCodeAndCode(SearchRunStatus.TABLECODE, code)).thenReturn(Optional.of(status));
        }
        when(users.getReferenceById(USER)).thenReturn(new UserET());
        when(dates.today()).thenReturn(new DateTableET());
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        pipeline = new SearchPipeline(dao, configs, masters, users, dates, apollo, qualification, emails, exclusions,
                creation, drafts, tx, new ObjectMapper(), properties, Runnable::run);
    }

    private static ApolloSearchPersonDTO person(String id) {
        ApolloSearchPersonDTO p = new ApolloSearchPersonDTO();
        p.setId(id);
        p.setFirstName("Nombre " + id);
        p.setTitle("Gerente de TI");
        return p;
    }

    private void apolloReturns(List<ApolloSearchPersonDTO> people, int total) {
        when(apollo.searchPage(any(), eq(1), eq(ApolloClient.MAX_PER_PAGE)))
                .thenReturn(new ApolloSearchPage(total, 1, ApolloClient.MAX_PER_PAGE, people, ApolloRateLimit.UNKNOWN));
    }

    private static GeminiQualificationResult score(int value, boolean qualified) {
        return GeminiQualificationResult.builder().score(value).qualified(qualified).reason("razón").build();
    }

    private static EmailDiscoveryResult found(String email, String domain) {
        ApolloOrganizationDTO org = new ApolloOrganizationDTO();
        org.setPrimaryDomain(domain);
        ApolloPersonDTO person = new ApolloPersonDTO();
        person.setOrganization(org);
        return EmailDiscoveryResult.builder().outcome(Outcome.FOUND).email(email).source("APOLLO").person(person)
                .build();
    }

    private Map<String, String> seenResults() {
        Map<String, String> results = new java.util.LinkedHashMap<>();
        seen.forEach(s -> results.put(s.getProviderId(), s.getResult()));
        return results;
    }

    @Test
    void cadaPersonaNuevaTerminaComoContactoDescartadaSinCorreoOExcluida() {
        apolloReturns(List.of(person("p1"), person("p2"), person("p3"), person("p4"), person("p5"), person("p6")), 40);
        when(qualification.qualify(any(), any())).thenReturn(List.of(
                GeminiQualificationResult.builder().error("BLOCKED").build(),
                score(30, false), score(80, true), score(85, true), score(90, true), score(70, true)));
        when(emails.discover(eq("p3"), any())).thenReturn(EmailDiscoveryResult.builder().outcome(Outcome.NOT_FOUND).build());
        when(emails.discover(eq("p4"), any())).thenReturn(found("ana@ya-existe.example", "ya-existe.example"));
        when(emails.discover(eq("p5"), any())).thenReturn(found("luis@nueva.example", "nueva.example"));
        when(emails.discover(eq("p6"), any())).thenReturn(found("eva@otra.example", "otra.example"));
        when(exclusions.exclusionReason(eq("ana@ya-existe.example"), any(), any()))
                .thenReturn(Optional.of(Reason.EXISTING_COMPANY));
        when(exclusions.exclusionReason(eq("luis@nueva.example"), any(), any())).thenReturn(Optional.empty());
        when(exclusions.exclusionReason(eq("eva@otra.example"), any(), any())).thenReturn(Optional.empty());
        when(creation.createProspect(any(), any(), eq(7), any(), eq(USER))).thenReturn(101, 102);
        when(drafts.generate(102, USER)).thenThrow(new IllegalStateException("Falta el mensaje base"));

        SearchPipeline.Summary summary = pipeline.execute(7, USER);

        // p1 no se pudo calificar: no queda como vista, se intenta otra vez mañana.
        assertThat(seenResults()).containsExactly(
                Map.entry("p2", ApolloSeenET.RESULT_DISCARDED_AI),
                Map.entry("p3", ApolloSeenET.RESULT_NO_EMAIL),
                Map.entry("p4", ApolloSeenET.RESULT_EXCLUDED),
                Map.entry("p5", ApolloSeenET.RESULT_CREATED),
                Map.entry("p6", ApolloSeenET.RESULT_CREATED));
        assertThat(seen.get(3).getPmLeafletContactId()).isEqualTo(101);
        assertThat(summary).isEqualTo(new SearchPipeline.Summary(40, 6, 4, 2, 1, summary.stopReason()));
        assertThat(run.getFoundCount()).isEqualTo(40);
        assertThat(run.getNewCount()).isEqualTo(6);
        assertThat(run.getQualifiedCount()).isEqualTo(4);
        assertThat(run.getStatus().getCode()).isEqualTo(SearchRunStatus.FINISHED);
        assertThat(run.getErrorDetail()).contains("no se pudieron calificar", "sin borrador", "Falta el mensaje base");
        // Los descartados por la IA no gastan créditos; los créditos se cargan a la búsqueda.
        verify(emails, never()).discover(eq("p2"), any());
        ArgumentCaptor<CreditContext> credits = ArgumentCaptor.forClass(CreditContext.class);
        verify(emails, times(4)).discover(anyString(), credits.capture());
        assertThat(credits.getValue()).isEqualTo(CreditContext.forSearchRun(7, USER));
        verify(drafts).generate(101, USER);
    }

    @Test
    void losCompetidoresNiSeBuscanEnApollo() {
        apolloReturns(List.of(), 0);

        pipeline.execute(7, USER);

        ArgumentCaptor<ApolloPeopleSearchRequest> request = ArgumentCaptor.forClass(ApolloPeopleSearchRequest.class);
        verify(apollo).searchPage(request.capture(), eq(1), anyInt());
        assertThat(request.getValue().getNotOrganizationWebsitesList()).containsExactly("competidor.com");
        assertThat(request.getValue().getPersonTitles()).containsExactly("Gerente de TI");
        assertThat(run.getStatus().getCode()).isEqualTo(SearchRunStatus.FINISHED);
        verify(qualification, never()).qualify(any(), any());
    }

    @Test
    void soloTomaPersonasQueNuncaSeVieronHastaElTopePorEjecucion() {
        properties.getSearch().setMaxNewPerRun(3);
        List<ApolloSearchPersonDTO> page = IntStream.rangeClosed(1, 100).mapToObj(i -> person("p" + i)).toList();
        when(apollo.searchPage(any(), eq(1), anyInt()))
                .thenReturn(new ApolloSearchPage(250, 1, 100, page, ApolloRateLimit.UNKNOWN));
        when(dao.findSeen(anyCollection())).thenReturn(Set.of("p1", "p3"));

        SearchPipeline.Collected collected = pipeline.collectNew(new ApolloPeopleSearchRequest());

        assertThat(collected.people()).extracting(ApolloSearchPersonDTO::getId).containsExactly("p2", "p4", "p5");
        assertThat(collected.totalEntries()).isEqualTo(250);
        verify(apollo, times(1)).searchPage(any(), anyInt(), anyInt());
    }

    @Test
    void siSeAcabanLosCreditosParaYDejaLasDemasParaManana() {
        apolloReturns(List.of(person("p1"), person("p2")), 2);
        when(qualification.qualify(any(), any())).thenReturn(List.of(score(80, true), score(90, true)));
        when(emails.discover(eq("p1"), any())).thenThrow(new CreditLimitExceededException("APOLLO",
                CreditLimitExceededException.Scope.SEARCH_RUN, BigDecimal.TEN, BigDecimal.TEN));

        SearchPipeline.Summary summary = pipeline.execute(7, USER);

        assertThat(seen).isEmpty();
        assertThat(summary.qualified()).isZero();
        assertThat(run.getStatus().getCode()).isEqualTo(SearchRunStatus.FINISHED);
        assertThat(run.getErrorDetail()).contains("tope de créditos de APOLLO (por búsqueda)");
        verify(emails, never()).discover(eq("p2"), any());
        verify(creation, never()).createProspect(any(), any(), any(), any(), any());
    }

    @Test
    void siApolloFallaLaBusquedaQuedaFallidaConElMotivo() {
        when(apollo.searchPage(any(), anyInt(), anyInt()))
                .thenThrow(new ApolloException.Configuration("Falta la llave de Apollo"));

        SearchPipeline.Summary summary = pipeline.execute(7, USER);

        assertThat(run.getStatus().getCode()).isEqualTo(SearchRunStatus.FAILED);
        assertThat(run.getErrorDetail()).contains("Falta la llave de Apollo");
        assertThat(summary.created()).isZero();
    }
}
