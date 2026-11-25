package co.com.gtc.prospeccion.integration.hunter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterAccountResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterEmailFinderResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterVerifierResponse;

/** Cliente de Hunter (4.1) con respuestas simuladas: nunca llama a la API real ni gasta créditos. */
class HunterClientTest {

    static final String KEY = "llave-ficticia-de-prueba";
    static final String BASE = "https://hunter.test/v2";
    static final String FINDER_URL = BASE + "/email-finder?domain=empresa-demo.co&first_name=Ana&last_name=Ficticia";
    static final String VERIFIER_URL = BASE + "/email-verifier?email=ana@empresa-demo.co";

    private final List<Duration> waits = new ArrayList<>();
    private MockRestServiceServer hunter;
    private RestClient restClient;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        hunter = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HunterClient client(String key) {
        Provider properties = new Provider();
        properties.setBaseUrl(BASE);
        properties.setMaxRetries(2);
        return new HunterClient(restClient, properties, () -> key, new ObjectMapper(), waits::add);
    }

    private HunterClient client() {
        return client(KEY);
    }

    private static HttpHeaders credits(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Credits-Used", value);
        return headers;
    }

    private static String error(String id, int code) {
        return "{\"errors\": [{\"id\": \"" + id + "\", \"code\": " + code + ", \"details\": \"detalle\"}]}";
    }

    // ---------- Email Finder ----------

    @Test
    void finderEnviaLaLlaveEnElEncabezadoYLeeData() {
        hunter.expect(requestTo(FINDER_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess("""
                        {"data": {"first_name": "Ana", "last_name": "Ficticia", "email": "ana@empresa-demo.co",
                          "score": 94, "domain": "empresa-demo.co", "accept_all": false, "position": "CTO",
                          "verification": {"date": "2026-11-01", "status": "valid"}}, "meta": {}}
                        """, MediaType.APPLICATION_JSON).headers(credits("1")));

        HunterEmailFinderResponse found = client().findEmail("empresa-demo.co", "Ana", "Ficticia").orElseThrow();

        hunter.verify(); // la llave no va en la URL (requestTo exige la URL exacta)
        assertThat(found.getEmail()).isEqualTo("ana@empresa-demo.co");
        assertThat(found.getScore()).isEqualTo(94);
        assertThat(found.verificationStatus()).isEqualTo("valid");
        assertThat(found.getCreditsUsed()).isEqualTo(1.0);
    }

    @Test
    void finderSinCorreoDevuelveVacio() {
        hunter.expect(requestTo(FINDER_URL)).andRespond(withSuccess(
                "{\"data\": {\"email\": null, \"score\": null}}", MediaType.APPLICATION_JSON).headers(credits("0")));
        hunter.expect(requestTo(FINDER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(error("email_not_found", 404)));

        assertThat(client().findEmail("empresa-demo.co", "Ana", "Ficticia")).isEmpty();
        assertThat(client().findEmail("empresa-demo.co", "Ana", "Ficticia")).isEmpty();
    }

    @Test
    void finder451EsPersonaQuePidioNoSerProcesada() {
        hunter.expect(requestTo(FINDER_URL)).andRespond(withStatus(HttpStatusCode.valueOf(451))
                .contentType(MediaType.APPLICATION_JSON).body(error("claimed_email", 451)));

        assertThatThrownBy(() -> client().findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .isInstanceOf(HunterException.OptedOut.class);
    }

    @Test
    void cupoDelPlanAgotadoNoSeReintenta() {
        hunter.expect(requestTo(FINDER_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(error("too_many_requests", 429)));

        assertThatThrownBy(() -> client().findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .isInstanceOf(HunterException.QuotaExhausted.class);
        assertThat(waits).isEmpty();
    }

    @Test
    void limitePorSegundoEsperaYReintenta() {
        hunter.expect(requestTo(FINDER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(error("too_many_requests", 403)));
        hunter.expect(requestTo(FINDER_URL)).andRespond(withSuccess(
                "{\"data\": {\"email\": \"ana@empresa-demo.co\", \"score\": 90}}", MediaType.APPLICATION_JSON));

        assertThat(client().findEmail("empresa-demo.co", "Ana", "Ficticia")).isPresent();
        assertThat(waits).hasSize(1);
    }

    @Test
    void errorDelServidorAgotaLosReintentos() {
        hunter.expect(times(3), requestTo(FINDER_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client().findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .isInstanceOf(HunterException.RetriesExhausted.class);
        assertThat(waits).hasSize(2);
    }

    @Test
    void sinLlaveOParametrosNoLlama() {
        assertThatThrownBy(() -> client(" ").findEmail("empresa-demo.co", "Ana", "Ficticia"))
                .isInstanceOf(HunterException.Configuration.class);
        assertThatThrownBy(() -> client().findEmail("empresa-demo.co", "Ana", null))
                .isInstanceOf(IllegalArgumentException.class);
        hunter.verify();
    }

    // ---------- Email Verifier ----------

    @Test
    void verifierLeeElEstadoYLosCreditos() {
        hunter.expect(requestTo(VERIFIER_URL))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess("""
                        {"data": {"status": "accept_all", "result": "risky", "score": 70, "email": "ana@empresa-demo.co",
                          "regexp": true, "gibberish": false, "disposable": false, "webmail": false,
                          "mx_records": true, "smtp_server": true, "smtp_check": false, "accept_all": true,
                          "block": false}}
                        """, MediaType.APPLICATION_JSON).headers(credits("0.5")));

        HunterVerifierResponse result = client().verifyEmail("ana@empresa-demo.co");

        assertThat(result.getStatus()).isEqualTo(HunterVerifierResponse.ACCEPT_ALL);
        assertThat(result.getScore()).isEqualTo(70);
        assertThat(result.getAcceptAll()).isTrue();
        assertThat(result.getCreditsUsed()).isEqualTo(0.5);
    }

    @Test
    void verificacionEnCursoSeVuelveAConsultar() {
        hunter.expect(requestTo(VERIFIER_URL)).andRespond(withStatus(HttpStatus.ACCEPTED));
        hunter.expect(requestTo(VERIFIER_URL)).andRespond(withSuccess(
                "{\"data\": {\"status\": \"valid\", \"score\": 98}}", MediaType.APPLICATION_JSON));

        assertThat(client().verifyEmail("ana@empresa-demo.co").getStatus()).isEqualTo("valid");
        assertThat(waits).containsExactly(HunterClient.PENDING_WAIT);
    }

    @Test
    void siLaVerificacionNoTerminaSeRinde() {
        hunter.expect(times(HunterClient.MAX_PENDING_CHECKS), requestTo(VERIFIER_URL))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> client().verifyEmail("ana@empresa-demo.co"))
                .isInstanceOf(HunterException.RetriesExhausted.class);
    }

    @Test
    void verifierCorreoInvalidoEsRechazo() {
        hunter.expect(requestTo(VERIFIER_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body(error("invalid_email", 400)));

        assertThatThrownBy(() -> client().verifyEmail("ana@empresa-demo.co"))
                .isInstanceOfSatisfying(HunterException.Rejected.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ---------- Cuenta (4.2) ----------

    @Test
    void cuentaDevuelveElSaldoDeLaBolsaUnica() {
        hunter.expect(requestTo(BASE + "/account"))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess("""
                        {"data": {"plan_name": "Starter", "reset_date": "2026-12-01",
                          "requests": {"credits": {"used": 120.5, "available": 2000, "remaining": 1879.5},
                                       "searches": {"used": 100, "available": 1000, "remaining": 900},
                                       "verifications": {"used": 41, "available": 2000, "remaining": 1959}}}}
                        """, MediaType.APPLICATION_JSON));

        HunterAccountResponse cuenta = client().account();

        assertThat(cuenta.getPlanName()).isEqualTo("Starter");
        assertThat(cuenta.remainingCredits()).isEqualByComparingTo("1879.5");
    }
}
