package co.com.gtc.prospeccion.credits.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.credits.CreditCharge;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException.Scope;
import co.com.gtc.prospeccion.credits.dto.CreditSummaryResponseDTO;
import co.com.gtc.prospeccion.credits.dto.CreditSummaryResponseDTO.Status;
import co.com.gtc.prospeccion.credits.entities.CreditLedgerET;
import co.com.gtc.prospeccion.credits.repository.CreditLedgerRepository;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.integration.hunter.HunterClient;
import co.com.gtc.prospeccion.integration.hunter.HunterException;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterAccountResponse;
import co.com.gtc.prospeccion.security.CurrentUserProvider;

class CreditLedgerServiceImplTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 23);
    static final LocalDate INICIO_MES = LocalDate.of(2026, 11, 1);

    private final CreditLedgerRepository repository = mock(CreditLedgerRepository.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final HunterClient hunter = mock(HunterClient.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final DateTableET hoy = new DateTableET();
    private final UserET usuarioPeticion = new UserET();
    private CreditLedgerServiceImpl service;

    @BeforeEach
    void preparar() {
        ReflectionTestUtils.setField(hoy, "id", 700);
        ReflectionTestUtils.setField(hoy, "date", HOY);
        when(dateTable.today()).thenReturn(hoy);
        when(currentUser.getCurrentUser()).thenReturn(usuarioPeticion);
        when(repository.sumCreditsSince(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(repository.sumCreditsForSearchRun(anyString(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(repository.sumCostUsdSince(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(hunter.account()).thenThrow(new HunterException.Configuration("sin llave"));
        // Por defecto: Apollo 1000 al mes y 50 por campaña; Hunter 500 al mes y 50 por campaña.
        service = new CreditLedgerServiceImpl(repository, dateTable, currentUser, userRepository, hunter, properties);
    }

    // ---------- topes ----------

    @Test
    void dentroDeLosTopesDejaSeguir() {
        when(repository.sumCreditsForSearchRun("APOLLO", 41)).thenReturn(new BigDecimal("49"));
        when(repository.sumCreditsSince("APOLLO", INICIO_MES)).thenReturn(new BigDecimal("999"));

        assertThatCode(() -> service.ensureAvailable("APOLLO", 41, BigDecimal.ONE)).doesNotThrowAnyException();
    }

    @Test
    void elTopePorCampanaDetieneAntesDeGastar() {
        when(repository.sumCreditsForSearchRun("APOLLO", 41)).thenReturn(new BigDecimal("50"));

        assertThatThrownBy(() -> service.ensureAvailable("APOLLO", 41, BigDecimal.ONE))
                .isInstanceOfSatisfying(CreditLimitExceededException.class, e -> {
                    assertThat(e.getScope()).isEqualTo(Scope.SEARCH_RUN);
                    assertThat(e.getProvider()).isEqualTo("APOLLO");
                    assertThat(e.getMessageKey()).isEqualTo("lblCreditLimit");
                });
    }

    @Test
    void elPresupuestoMensualCuentaDesdeElDiaUnoDelMes() {
        when(repository.sumCreditsSince("HUNTER", INICIO_MES)).thenReturn(new BigDecimal("499.75"));

        assertThatThrownBy(() -> service.ensureAvailable("HUNTER", null, new BigDecimal("0.5")))
                .isInstanceOfSatisfying(CreditLimitExceededException.class,
                        e -> assertThat(e.getScope()).isEqualTo(Scope.MONTHLY));
        // Sin campaña no se consulta el tope por campaña.
        verify(repository, never()).sumCreditsForSearchRun(anyString(), anyInt());
    }

    @Test
    void topeEnCeroEsSinTopeYGeminiNoTieneTope() {
        properties.getCredits().getApollo().setMonthlyBudget(BigDecimal.ZERO);
        properties.getCredits().getApollo().setPerSearchRun(BigDecimal.ZERO);
        when(repository.sumCreditsSince("APOLLO", INICIO_MES)).thenReturn(new BigDecimal("100000"));

        assertThatCode(() -> service.ensureAvailable("APOLLO", 41, BigDecimal.ONE)).doesNotThrowAnyException();
        assertThatCode(() -> service.ensureAvailable("GEMINI", 41, new BigDecimal("999999"))).doesNotThrowAnyException();
    }

    // ---------- libro ----------

    @Test
    void anotaElGastoConAuditoriaYCostoEstimado() {
        properties.getCredits().getHunter().setUnitCostUsd(new BigDecimal("0.049"));
        UserET creador = new UserET();
        when(userRepository.getReferenceById(7)).thenReturn(creador);

        service.record(new CreditCharge("HUNTER", "EMAIL_VERIFIER", new BigDecimal("0.5"), 41, 900, 7));

        ArgumentCaptor<CreditLedgerET> guardado = ArgumentCaptor.forClass(CreditLedgerET.class);
        verify(repository).save(guardado.capture());
        CreditLedgerET e = guardado.getValue();
        assertThat(e.getProvider()).isEqualTo("HUNTER");
        assertThat(e.getOperation()).isEqualTo("EMAIL_VERIFIER");
        assertThat(e.getCredits()).isEqualByComparingTo("0.5");
        assertThat(e.getCostUsd()).isEqualByComparingTo("0.0245");
        assertThat(e.getSearchRunId()).isEqualTo(41);
        assertThat(e.getPmLeafletContactId()).isEqualTo(900);
        assertThat(e.getCreatedBy()).isSameAs(creador); // el usuario de la campaña, no el de la petición
        assertThat(e.getCreatedDate()).isSameAs(hoy);
    }

    @Test
    void sinUsuarioUsaElDeLaPeticionYSinPrecioNoCalculaCosto() {
        service.record(new CreditCharge("APOLLO", "PEOPLE_ENRICHMENT", BigDecimal.ONE, null, null, null));

        ArgumentCaptor<CreditLedgerET> guardado = ArgumentCaptor.forClass(CreditLedgerET.class);
        verify(repository).save(guardado.capture());
        assertThat(guardado.getValue().getCreatedBy()).isSameAs(usuarioPeticion);
        assertThat(guardado.getValue().getCostUsd()).isNull();
    }

    @Test
    void losGastosEnCeroNoSeAnotan() {
        service.record(new CreditCharge("HUNTER", "EMAIL_FINDER", BigDecimal.ZERO, 41, null, 7));

        verify(repository, never()).save(any());
    }

    // ---------- resumen y alertas ----------

    @Test
    void resumenDelMesConPorcentajeYEstado() {
        when(repository.sumCreditsSince("APOLLO", INICIO_MES)).thenReturn(new BigDecimal("812"));
        when(repository.sumCreditsSince("HUNTER", INICIO_MES)).thenReturn(new BigDecimal("120.5"));

        List<CreditSummaryResponseDTO> resumen = service.getSummary();

        CreditSummaryResponseDTO apollo = resumen.get(0);
        assertThat(apollo.getProvider()).isEqualTo("APOLLO");
        assertThat(apollo.getPeriod()).isEqualTo("2026-11");
        assertThat(apollo.getPercentUsed()).isEqualTo(81);
        assertThat(apollo.getStatus()).isEqualTo(Status.WARNING); // 80 % o más
        assertThat(apollo.getPerSearchRunLimit()).isEqualByComparingTo("50");
        CreditSummaryResponseDTO hunterResumen = resumen.get(1);
        assertThat(hunterResumen.getPercentUsed()).isEqualTo(24);
        assertThat(hunterResumen.getStatus()).isEqualTo(Status.OK);
        assertThat(hunterResumen.getProviderRemaining()).isNull(); // sin llave de Hunter el resumen sale igual
    }

    @Test
    void presupuestoAgotadoQuedaExcedido() {
        when(repository.sumCreditsSince("APOLLO", INICIO_MES)).thenReturn(new BigDecimal("1000"));

        assertThat(service.getSummary().get(0).getStatus()).isEqualTo(Status.EXCEEDED);
    }

    @Test
    void siElSaldoDeHunterNoAlcanzaUnaCampanaAvisa() {
        HunterAccountResponse cuenta = new HunterAccountResponse();
        HunterAccountResponse.Requests requests = new HunterAccountResponse.Requests();
        HunterAccountResponse.Usage searches = new HunterAccountResponse.Usage();
        searches.setRemaining(new BigDecimal("20"));
        HunterAccountResponse.Usage verifications = new HunterAccountResponse.Usage();
        verifications.setRemaining(new BigDecimal("40"));
        requests.setSearches(searches);
        requests.setVerifications(verifications);
        cuenta.setRequests(requests);
        doReturn(cuenta).when(hunter).account();

        CreditSummaryResponseDTO hunterResumen = service.getSummary().get(1);

        assertThat(hunterResumen.getProviderRemaining()).isEqualByComparingTo("40"); // 20 + 40 x 0,5
        assertThat(hunterResumen.getStatus()).isEqualTo(Status.WARNING); // 40 < 50 por campaña
    }

    @Test
    void lasAlertasSonLosProveedoresQueNoEstanEnOk() {
        when(repository.sumCreditsSince(eq("APOLLO"), any())).thenReturn(new BigDecimal("1000"));

        List<CreditSummaryResponseDTO> alertas = service.checkLowCreditAlerts();

        assertThat(alertas).extracting(CreditSummaryResponseDTO::getProvider).containsExactly("APOLLO");
    }

    @Test
    void elSaldoDeHunterSaleDeCupoMenosUsadoCuandoNoTraeRemaining() {
        // Formato de Hunter: available = cupo del plan, used = lo gastado.
        HunterAccountResponse cuenta = new HunterAccountResponse();
        HunterAccountResponse.Requests requests = new HunterAccountResponse.Requests();
        HunterAccountResponse.Usage searches = new HunterAccountResponse.Usage();
        searches.setAvailable(new BigDecimal("25"));
        searches.setUsed(new BigDecimal("3"));
        HunterAccountResponse.Usage verifications = new HunterAccountResponse.Usage();
        verifications.setAvailable(new BigDecimal("50"));
        verifications.setUsed(new BigDecimal("2"));
        requests.setSearches(searches);
        requests.setVerifications(verifications);
        cuenta.setRequests(requests);
        doReturn(cuenta).when(hunter).account();

        CreditSummaryResponseDTO hunterResumen = service.getSummary().get(1);

        assertThat(hunterResumen.getProviderRemaining()).isEqualByComparingTo("46"); // 22 + 48 x 0,5
        assertThat(hunterResumen.getProviderNote()).isNull();
    }

    @Test
    void siNoSePuedeLeerElSaldoDiceElMotivo() {
        CreditSummaryResponseDTO hunterResumen = service.getSummary().get(1);

        assertThat(hunterResumen.getProviderRemaining()).isNull();
        assertThat(hunterResumen.getProviderNote()).isEqualTo("sin llave");
    }
}
