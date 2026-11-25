package co.com.gtc.prospeccion.integration.apollo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;

/** People Enrichment (4.1) con respuestas simuladas: nunca llama a la API real ni gasta créditos. */
class ApolloClientEnrichTest {

    static final String KEY = "llave-ficticia-de-prueba";
    static final String BASE = "https://apollo.test/api/v1";
    static final String MATCH_URL = BASE
            + "/people/match?id=abc123&reveal_personal_emails=false&reveal_phone_number=false";

    private final List<Duration> waits = new ArrayList<>();
    private MockRestServiceServer apollo;
    private ApolloClient client;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        apollo = MockRestServiceServer.bindTo(builder).build();
        Provider properties = new Provider();
        properties.setBaseUrl(BASE);
        properties.setMaxRetries(2);
        client = new ApolloClient(builder.build(), properties, () -> KEY, new ObjectMapper(), waits::add);
    }

    private void respond(String json) {
        apollo.expect(requestTo(MATCH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", KEY))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void traeCorreoApellidoYDominioSinPedirCorreosPersonalesNiTelefono() {
        respond("""
                {"person": {"id": "abc123", "first_name": "Ana", "last_name": "Ficticia", "title": "CTO",
                  "email": "ana.ficticia@empresa-demo.co", "email_status": "verified", "seniority": "c_suite",
                  "organization": {"name": "Empresa Demo", "primary_domain": "empresa-demo.co",
                    "industry": "hospital & health care", "estimated_num_employees": 250}}}
                """);

        ApolloPersonDTO person = client.enrichPerson("abc123").orElseThrow();

        apollo.verify(); // la URL exacta lleva reveal_personal_emails=false y reveal_phone_number=false
        assertThat(person.getEmail()).isEqualTo("ana.ficticia@empresa-demo.co");
        assertThat(person.getEmailStatus()).isEqualTo("verified");
        assertThat(person.getLastName()).isEqualTo("Ficticia");
        assertThat(person.getOrganization().getPrimaryDomain()).isEqualTo("empresa-demo.co");
        assertThat(person.getOrganization().getEstimatedNumEmployees()).isEqualTo(250);
    }

    @Test
    void elCorreoDeRellenoDeApolloSeTrataComoSinCorreo() {
        respond("""
                {"person": {"id": "abc123", "first_name": "Ana", "last_name": "Ficticia",
                  "email": "email_not_unlocked@domain.com", "email_status": "unavailable",
                  "organization": {"primary_domain": "empresa-demo.co"}}}
                """);

        assertThat(client.enrichPerson("abc123").orElseThrow().getEmail()).isNull();
    }

    @Test
    void sinCoincidenciaDevuelveVacio() {
        respond("{\"person\": null}");

        assertThat(client.enrichPerson("abc123")).isEqualTo(Optional.empty());
    }

    @Test
    void llaveInvalidaSeRechazaSinReintentar() {
        apollo.expect(requestTo(MATCH_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\": \"Invalid access credentials.\"}"));

        assertThatThrownBy(() -> client.enrichPerson("abc123"))
                .isInstanceOf(ApolloException.Rejected.class)
                .hasMessageContaining("enriquecimientos");
        assertThat(waits).isEmpty();
    }

    @Test
    void sinIdNoLlamaAApollo() {
        assertThatThrownBy(() -> client.enrichPerson(" ")).isInstanceOf(IllegalArgumentException.class);
        apollo.verify();
    }

    @Test
    void correoUtil() {
        assertThat(ApolloClient.usableEmail(null)).isNull();
        assertThat(ApolloClient.usableEmail("sin-arroba")).isNull();
        assertThat(ApolloClient.usableEmail("EMAIL_NOT_UNLOCKED@x.com")).isNull();
        assertThat(ApolloClient.usableEmail(" ana@empresa.co ")).isEqualTo("ana@empresa.co");
    }
}
