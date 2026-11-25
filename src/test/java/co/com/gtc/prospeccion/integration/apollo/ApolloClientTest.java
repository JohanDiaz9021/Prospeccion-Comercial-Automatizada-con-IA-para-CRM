package co.com.gtc.prospeccion.integration.apollo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;

/** Pruebas del cliente de Apollo People API Search con respuestas simuladas: nunca llaman a la API real. */
class ApolloClientTest {

    static final String KEY = "llave-ficticia-de-prueba";
    static final String BASE = "https://apollo.test/api/v1";

    private final List<Duration> waits = new ArrayList<>();
    private MockRestServiceServer apollo;
    private RestClient restClient;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        apollo = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private ApolloClient client() {
        return client(KEY);
    }

    private ApolloClient client(String key) {
        Provider properties = new Provider();
        properties.setBaseUrl(BASE);
        properties.setMaxRetries(3);
        return new ApolloClient(restClient, properties, () -> key, new ObjectMapper(), waits::add);
    }

    private static MultiValueMap<String, String> decodedQuery(URI uri) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            result.add(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1].replace("+", "%2B"), StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static ApolloPeopleSearchRequest filtros() {
        return ApolloPeopleSearchRequest.builder()
                .personTitles(List.of("Gerente de TI", "Director de Tecnología"))
                .personLocations(List.of("Bogota, Colombia"))
                .organizationNumEmployeesRanges(List.of("51,200"))
                .organizationIndustries(List.of("hospital & health care"))
                .build();
    }

    /** Respuesta con el formato de la documentación de Apollo: total_entries + people. */
    private static String page(int total, int fromId, int count) {
        String people = IntStream.range(fromId, fromId + count).mapToObj(i -> """
                {"id": "p%d", "first_name": "Nombre%d", "last_name_obfuscated": "Ap***o", "title": "Gerente de TI",
                 "has_email": %s, "has_city": true, "has_direct_phone": "Yes",
                 "organization": {"name": "Clínica %d", "has_industry": true, "has_employee_count": true}}
                """.formatted(i, i, i % 2 == 0, i)).collect(Collectors.joining(","));
        return "{\"total_entries\": %d, \"people\": [%s]}".formatted(total, people);
    }

    private static ResponseCreator ok(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-minute-requests-left", "49");
        headers.add("x-hourly-requests-left", "199");
        headers.add("x-24-hour-requests-left", "599");
        return withSuccess(body, MediaType.APPLICATION_JSON).headers(headers);
    }

    private static ResponseCreator tooMany(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.add("retry-after", retryAfter);
        }
        return withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\": \"The maximum number of api calls allowed for api/v1/mixed_people/api_search is 600 times per day.\"}");
    }

    @Test
    void envíaFiltrosComoQueryParamsYLaLlaveEnElEncabezado() {
        apollo.expect(requestTo(Matchers.startsWith(BASE + "/mixed_people/api_search?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", KEY))
                .andExpect(request -> {
                    // Se decodifica la URL: lo que importa es lo que Apollo lee (los [] viajan como %5B%5D).
                    MultiValueMap<String, String> q = decodedQuery(request.getURI());
                    assertThat(q.get("person_titles[]")).containsExactly("Gerente de TI", "Director de Tecnología");
                    assertThat(q.get("person_locations[]")).containsExactly("Bogota, Colombia");
                    assertThat(q.get("organization_num_employees_ranges[]")).containsExactly("51,200");
                    assertThat(q.get("organization_industries[]")).containsExactly("hospital & health care");
                    assertThat(q.getFirst("page")).isEqualTo("2");
                    assertThat(q.getFirst("per_page")).isEqualTo("25");
                    assertThat(request.getURI().toString()).doesNotContain(KEY);
                })
                .andRespond(ok(page(3, 1, 3)));

        client().searchPage(filtros(), 2, 25);

        apollo.verify();
    }

    @Test
    void leeLasPersonasSinCorreoYElCupoRestante() {
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(ok(page(1234, 1, 2)));

        ApolloSearchPage result = client().searchPage(filtros(), 1, 100);

        assertThat(result.totalEntries()).isEqualTo(1234);
        assertThat(result.people()).hasSize(2);
        ApolloSearchPersonDTO person = result.people().get(0);
        assertThat(person.getId()).isEqualTo("p1");
        assertThat(person.getFirstName()).isEqualTo("Nombre1");
        assertThat(person.getLastNameObfuscated()).isEqualTo("Ap***o");
        assertThat(person.getHasEmail()).isFalse();
        assertThat(result.people().get(1).getHasEmail()).isTrue();
        assertThat(person.getHasDirectPhone()).isEqualTo("Yes");
        assertThat(person.getOrganization().getName()).isEqualTo("Clínica 1");
        assertThat(result.rateLimit()).isEqualTo(new ApolloSearchPage.ApolloRateLimit(49, 199, 599));
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void validaLosLimitesDePaginacion() {
        ApolloClient client = client();
        assertThatThrownBy(() -> client.searchPage(filtros(), 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchPage(filtros(), 501, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchPage(filtros(), 1, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recorrePaginasHastaAcabarLosResultados() {
        apollo.expect(requestTo(Matchers.containsString("page=1&"))).andRespond(ok(page(250, 1, 100)));
        apollo.expect(requestTo(Matchers.containsString("page=2&"))).andRespond(ok(page(250, 101, 100)));
        apollo.expect(requestTo(Matchers.containsString("page=3&"))).andRespond(ok(page(250, 201, 50)));

        ApolloSearchResult result = client().searchAll(filtros(), 1000);

        apollo.verify();
        assertThat(result.people()).hasSize(250);
        assertThat(result.pagesFetched()).isEqualTo(3);
        assertThat(result.totalEntries()).isEqualTo(250);
    }

    @Test
    void seDetieneAlJuntarElMaximoPedido() {
        apollo.expect(times(1), requestTo(Matchers.startsWith(BASE))).andRespond(ok(page(5000, 1, 100)));

        ApolloSearchResult result = client().searchAll(filtros(), 30);

        apollo.verify();
        assertThat(result.people()).hasSize(30);
        assertThat(result.pagesFetched()).isEqualTo(1);
    }

    @Test
    void noPasaDelTopeDe50000Resultados() {
        ApolloSearchPage ultima = new ApolloSearchPage(80_000, 500, 100, List.of(new ApolloSearchPersonDTO()),
                ApolloSearchPage.ApolloRateLimit.UNKNOWN);
        ApolloSearchPage penultima = new ApolloSearchPage(80_000, 499, 100, List.of(new ApolloSearchPersonDTO()),
                ApolloSearchPage.ApolloRateLimit.UNKNOWN);
        ApolloSearchPage vacia = new ApolloSearchPage(80_000, 3, 100, List.of(), ApolloSearchPage.ApolloRateLimit.UNKNOWN);

        assertThat(ultima.hasMore()).isFalse();
        assertThat(penultima.hasMore()).isTrue();
        assertThat(vacia.hasMore()).isFalse();
    }

    @Test
    void ante429CortoEsperaLoQuePideApolloYReintenta() {
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(tooMany("12"));
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(ok(page(1, 1, 1)));

        assertThat(client().searchPage(filtros(), 1, 100).people()).hasSize(1);
        assertThat(waits).containsExactly(Duration.ofSeconds(12));
    }

    @Test
    void ante429LargoNoBloqueaYAvisaCuandoReintentar() {
        apollo.expect(times(1), requestTo(Matchers.startsWith(BASE))).andRespond(tooMany("3600"));

        assertThatThrownBy(() -> client().searchPage(filtros(), 1, 100))
                .isInstanceOfSatisfying(ApolloException.RateLimited.class, e -> {
                    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofHours(1));
                    assertThat(e.getProvider()).isEqualTo("apollo");
                    assertThat(e.getMessage()).contains("600 times per day");
                });
        apollo.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void reintentaAnteErroresDelServidorYFallasDeRed() {
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(withException(new IOException("timeout")));
        apollo.expect(requestTo(Matchers.startsWith(BASE))).andRespond(ok(page(1, 1, 1)));

        client().searchPage(filtros(), 1, 100);

        assertThat(waits).hasSize(2);
        assertThat(waits.get(0)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(3));
        assertThat(waits.get(1)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5));
    }

    @Test
    void seRindeAlAgotarLosReintentos() {
        apollo.expect(times(4), requestTo(Matchers.startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client().searchPage(filtros(), 1, 100))
                .isInstanceOf(ApolloException.RetriesExhausted.class).hasMessageContaining("4 intentos");
        apollo.verify();
    }

    @Test
    void llaveSinAccesoNoSeReintenta() {
        apollo.expect(times(1), requestTo(Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"This API key is not authorized to access api/v1/mixed_people/api_search.\","
                                + " \"error_code\": \"API_INACCESSIBLE\"}"));

        assertThatThrownBy(() -> client().searchPage(filtros(), 1, 100))
                .isInstanceOfSatisfying(ApolloException.Rejected.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("not authorized").doesNotContain(KEY);
                });
        assertThat(waits).isEmpty();
    }

    @Test
    void sinLlaveNoSeLlamaAApollo() {
        apollo.expect(never(), requestTo(Matchers.startsWith(BASE)));

        assertThatThrownBy(() -> client("").searchPage(filtros(), 1, 100))
                .isInstanceOf(ApolloException.Configuration.class).hasMessageContaining("APOLLO_API_KEY");
        apollo.verify();
    }

    @Test
    void omiteLosFiltrosVacios() {
        ApolloPeopleSearchRequest soloCargo = ApolloPeopleSearchRequest.builder()
                .personTitles(List.of("CEO", " ")).personLocations(List.of()).build();

        URI uri = URI.create("https://x/?" + ApolloClient.toQueryParams(soloCargo).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join(",", e.getValue())).collect(Collectors.joining("&")));

        assertThat(ApolloClient.toQueryParams(soloCargo)).containsOnlyKeys("person_titles[]");
        assertThat(ApolloClient.toQueryParams(soloCargo).get("person_titles[]")).containsExactly("CEO");
        assertThat(uri.getQuery()).isEqualTo("person_titles[]=CEO");
    }
}
