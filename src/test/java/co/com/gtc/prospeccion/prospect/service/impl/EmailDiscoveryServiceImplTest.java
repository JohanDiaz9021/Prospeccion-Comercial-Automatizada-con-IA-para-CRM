package co.com.gtc.prospeccion.prospect.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.credits.CreditCharge;
import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.credits.service.CreditLedgerService;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloOrganizationDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.hunter.HunterClient;
import co.com.gtc.prospeccion.integration.hunter.HunterException;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterEmailFinderResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterVerifierResponse;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult.Outcome;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/** Reglas de la 4.1 con Apollo y Hunter simulados (personas y empresas ficticias). */
class EmailDiscoveryServiceImplTest {

    static final String ID = "apollo-1";
    static final CreditContext CTX = new CreditContext(41, null, 7);

    private final ApolloClient apollo = mock(ApolloClient.class);
    private final HunterClient hunter = mock(HunterClient.class);
    private final TenantConfigRepository repository = mock(TenantConfigRepository.class);
    private final CreditLedgerService credits = mock(CreditLedgerService.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private EmailDiscoveryServiceImpl service;

    @BeforeEach
    void preparar() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        service = new EmailDiscoveryServiceImpl(apollo, hunter, repository, new ObjectMapper(), credits, properties);
    }

    private static ApolloPersonDTO person(String email, String emailStatus, String domain) {
        ApolloPersonDTO person = new ApolloPersonDTO();
        person.setId(ID);
        person.setFirstName("Ana");
        person.setLastName("Ficticia");
        person.setEmail(email);
        person.setEmailStatus(emailStatus);
        ApolloOrganizationDTO organization = new ApolloOrganizationDTO();
        organization.setName("Empresa Demo");
        organization.setPrimaryDomain(domain);
        person.setOrganization(organization);
        return person;
    }

    private void apolloReturns(ApolloPersonDTO person) {
        when(apollo.enrichPerson(ID)).thenReturn(Optional.ofNullable(person));
    }

    private static HunterVerifierResponse verification(String status, Integer score) {
        HunterVerifierResponse response = new HunterVerifierResponse();
        response.setStatus(status);
        response.setScore(score);
        return response;
    }

    private static HunterEmailFinderResponse finder(String email, int score, String status) {
        HunterEmailFinderResponse response = new HunterEmailFinderResponse();
        response.setEmail(email);
        response.setScore(score);
        HunterEmailFinderResponse.Verification verification = new HunterEmailFinderResponse.Verification();
        verification.setStatus(status);
        response.setVerification(verification);
        response.setCreditsUsed(1.0);
        return response;
    }

    @Test
    void apolloNoReconoceLaPersona() {
        apolloReturns(null);

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.getApolloCredits()).isZero();
    }

    @Test
    void correoVerificadoPorApolloSeAceptaSinGastarHunter() {
        apolloReturns(person("ana@empresa-demo.co", "verified", "empresa-demo.co"));

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.FOUND);
        assertThat(result.getEmail()).isEqualTo("ana@empresa-demo.co");
        assertThat(result.getSource()).isEqualTo("APOLLO");
        assertThat(result.getVerification()).isEqualTo("APOLLO_VERIFIED");
        assertThat(result.getApolloCredits()).isEqualTo(1);
        assertThat(result.getPerson().getLastName()).isEqualTo("Ficticia");
        verify(hunter, never()).verifyEmail(any());
        verify(hunter, never()).findEmail(any(), any(), any());
    }

    @Test
    void correoDeApolloSinVerificarPasaPorHunter() {
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(verification("valid", 97));

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.FOUND);
        assertThat(result.getVerification()).isEqualTo("valid");
        assertThat(result.getScore()).isEqualTo(97);
        assertThat(result.getHunterVerificationCredits()).isEqualTo(0.5);
    }

    @Test
    void siNoSeConfiaEnApolloTambienVerifica() {
        properties.getEmailDiscovery().setTrustApolloVerified(false);
        apolloReturns(person("ana@empresa-demo.co", "verified", "empresa-demo.co"));
        when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(verification("valid", 97));

        assertThat(service.discover(ID, CTX).getVerification()).isEqualTo("valid");
    }

    @Test
    void correoInvalidoWebmailODesechableSeDescarta() {
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        for (String status : List.of("invalid", "webmail", "disposable", "unknown")) {
            when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(verification(status, 10));

            EmailDiscoveryResult result = service.discover(ID, CTX);

            assertThat(result.getOutcome()).as(status).isEqualTo(Outcome.DISCARDED);
            assertThat(result.getVerification()).isEqualTo(status);
        }
    }

    @Test
    void correoPersonalSeDescartaSinGastarVerificacion() {
        apolloReturns(person("ana.ficticia@Gmail.com", "verified", "empresa-demo.co"));

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISCARDED);
        assertThat(result.getVerification()).isEqualTo("PERSONAL_DOMAIN");
        verify(hunter, never()).verifyEmail(any());
    }

    @Test
    void losDominiosPersonalesSalenDelPerfilDelTenant() {
        TenantConfigET config = new TenantConfigET();
        config.setProfileOfInterest("{\"exclusions\": {\"freeEmailDomains\": [\"correo-gratis.co\"]}}");
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        apolloReturns(person("ana@correo-gratis.co", "verified", "empresa-demo.co"));

        assertThat(service.discover(ID, CTX).getVerification()).isEqualTo("PERSONAL_DOMAIN");
    }

    @Test
    void sinCorreoEnApolloLoBuscaHunterYSiYaVieneVerificadoNoGastaVerificacion() {
        apolloReturns(person(null, "unavailable", "empresa-demo.co"));
        when(hunter.findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .thenReturn(Optional.of(finder("ana@empresa-demo.co", 95, "valid")));

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.FOUND);
        assertThat(result.getSource()).isEqualTo("HUNTER");
        assertThat(result.getApolloCredits()).isZero();
        assertThat(result.getHunterSearchCredits()).isEqualTo(1.0);
        assertThat(result.getHunterVerificationCredits()).isZero();
        verify(hunter, never()).verifyEmail(any());
    }

    @Test
    void correoDeHunterConPuntajeBajoSeVerificaAparte() {
        apolloReturns(person(null, null, "empresa-demo.co"));
        when(hunter.findEmail(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(finder("ana@empresa-demo.co", 60, "accept_all")));
        when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(verification("accept_all", 70));

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.FOUND); // accept_all se acepta por defecto
        assertThat(result.getHunterSearchCredits()).isEqualTo(1.0);
        assertThat(result.getHunterVerificationCredits()).isEqualTo(0.5);
    }

    @Test
    void acceptAllSePuedeDejarFueraPorConfiguracion() {
        properties.getEmailDiscovery().setAcceptedHunterStatuses(List.of("valid"));
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(verification("accept_all", 70));

        assertThat(service.discover(ID, CTX).getOutcome()).isEqualTo(Outcome.DISCARDED);
    }

    @Test
    void hunterNoLoEncuentra() {
        apolloReturns(person(null, null, "empresa-demo.co"));
        when(hunter.findEmail(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        EmailDiscoveryResult result = service.discover(ID, CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(result.getHunterSearchCredits()).isZero(); // Hunter no cobra si no encuentra
    }

    @Test
    void sinDominioDeLaEmpresaNoSeLlamaAHunter() {
        apolloReturns(person(null, null, null));

        assertThat(service.discover(ID, CTX).getOutcome()).isEqualTo(Outcome.NOT_FOUND);
        verify(hunter, never()).findEmail(any(), any(), any());
    }

    @Test
    void personaQuePidioNoSerProcesadaQuedaMarcada() {
        apolloReturns(person(null, null, "empresa-demo.co"));
        when(hunter.findEmail(anyString(), anyString(), anyString()))
                .thenThrow(new HunterException.OptedOut("451"));

        assertThat(service.discover(ID, CTX).getOutcome()).isEqualTo(Outcome.OPTED_OUT);

        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        when(hunter.verifyEmail(anyString())).thenThrow(new HunterException.OptedOut("451"));

        assertThat(service.discover(ID, CTX).getOutcome()).isEqualTo(Outcome.OPTED_OUT);
    }

    @Test
    void cupoAgotadoSePropagaParaReintentarDespues() {
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        when(hunter.verifyEmail(anyString())).thenThrow(new HunterException.QuotaExhausted("429"));

        assertThatThrownBy(() -> service.discover(ID, CTX)).isInstanceOf(HunterException.QuotaExhausted.class);
    }

    @Test
    void elResultadoNoImprimeElCorreo() {
        apolloReturns(person("ana@empresa-demo.co", "verified", "empresa-demo.co"));

        assertThat(service.discover(ID, CTX).toString()).doesNotContain("ana@").doesNotContain("Ficticia");
    }

    // ---------- créditos (4.2) ----------

    @Test
    void revisaElTopeAntesDeLlamarYAnotaLoGastadoConLaCampana() {
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        HunterVerifierResponse valid = verification("valid", 97);
        valid.setCreditsUsed(0.5);
        when(hunter.verifyEmail("ana@empresa-demo.co")).thenReturn(valid);

        service.discover(ID, CTX);

        InOrder orden = inOrder(credits, apollo, hunter);
        orden.verify(credits).ensureAvailable(CreditCharge.APOLLO, 41, BigDecimal.ONE);
        orden.verify(apollo).enrichPerson(ID);
        orden.verify(credits).record(new CreditCharge("APOLLO", "PEOPLE_ENRICHMENT", BigDecimal.ONE, 41, null, 7));
        orden.verify(credits).ensureAvailable(CreditCharge.HUNTER, 41, BigDecimal.valueOf(0.5));
        orden.verify(hunter).verifyEmail("ana@empresa-demo.co");
        orden.verify(credits).record(new CreditCharge("HUNTER", "EMAIL_VERIFIER", BigDecimal.valueOf(0.5), 41, null, 7));
    }

    @Test
    void siApolloNoEntregaCorreoNoSeAnotaCredito() {
        apolloReturns(person(null, null, null));

        service.discover(ID, CTX);

        verify(credits, never()).record(any());
    }

    @Test
    void conElTopeAlcanzadoNoSeLlamaAlProveedor() {
        doThrow(new CreditLimitExceededException("APOLLO", CreditLimitExceededException.Scope.SEARCH_RUN,
                BigDecimal.valueOf(50), BigDecimal.valueOf(50)))
                .when(credits).ensureAvailable(eq(CreditCharge.APOLLO), any(), any());

        assertThatThrownBy(() -> service.discover(ID, CTX)).isInstanceOf(CreditLimitExceededException.class);
        verify(apollo, never()).enrichPerson(any());
    }

    @Test
    void siHunterSeQuedaSinTopeLoDeApolloYaQuedoAnotado() {
        apolloReturns(person("ana@empresa-demo.co", "unverified", "empresa-demo.co"));
        doThrow(new CreditLimitExceededException("HUNTER", CreditLimitExceededException.Scope.MONTHLY,
                BigDecimal.valueOf(500), BigDecimal.valueOf(500)))
                .when(credits).ensureAvailable(eq(CreditCharge.HUNTER), any(), any());

        assertThatThrownBy(() -> service.discover(ID, CTX)).isInstanceOf(CreditLimitExceededException.class);
        verify(credits).record(new CreditCharge("APOLLO", "PEOPLE_ENRICHMENT", BigDecimal.ONE, 41, null, 7));
        verify(hunter, never()).verifyEmail(any());
    }

    @Test
    void elFinderSoloSeAnotaSiEncuentra() {
        apolloReturns(person(null, null, "empresa-demo.co"));
        when(hunter.findEmail(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service.discover(ID, CTX);

        verify(credits).ensureAvailable(CreditCharge.HUNTER, 41, BigDecimal.valueOf(1.0));
        verify(credits, never()).record(any());
    }

    // ---------- Solo Hunter (herramienta local, sin Apollo) ----------

    @Test
    void soloHunterNoLlamaAApolloYAplicaLasMismasReglas() {
        when(hunter.findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .thenReturn(Optional.of(finder("ana@empresa-demo.co", 95, "valid")));

        EmailDiscoveryResult result = service.discoverWithHunter(" Ana ", "Ficticia", "https://www.Empresa-Demo.co/contacto", CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.FOUND);
        assertThat(result.getSource()).isEqualTo("HUNTER");
        assertThat(result.getHunterSearchCredits()).isEqualTo(1.0);
        assertThat(result.getApolloCredits()).isZero();
        verify(apollo, never()).enrichPerson(any());
        verify(credits).ensureAvailable(eq("HUNTER"), eq(41), any());
    }

    @Test
    void soloHunterConDominioPersonalNoGasta() {
        EmailDiscoveryResult result = service.discoverWithHunter("Ana", "Ficticia", "gmail.com", CTX);

        assertThat(result.getOutcome()).isEqualTo(Outcome.NOT_FOUND);
        verify(hunter, never()).findEmail(any(), any(), any());
    }

    @Test
    void normalizaElDominio() {
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain(" HTTPS://www.Empresa.co/contacto ")).isEqualTo("empresa.co");
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain("empresa.co")).isEqualTo("empresa.co");
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain(" ")).isNull();
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain("@bancodemo.com.co")).isEqualTo("bancodemo.com.co");
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain("ana.ficticia@Empresa.co")).isEqualTo("empresa.co");
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain("empresa.co.")).isEqualTo("empresa.co");
        assertThat(EmailDiscoveryServiceImpl.normalizeDomain("@")).isNull();
    }
}
