package co.com.gtc.prospeccion.integration.gemini;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.GeminiProvider;

/** Pruebas del cliente único de Gemini con respuestas simuladas: nunca llaman a la API real. */
class GeminiClientTest {

    static final String KEY = "clave-ficticia-de-prueba";
    static final String URL = "https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent";
    static final String OK_BODY = """
            {"candidates": [{"content": {"role": "model", "parts": [{"text": "Hola"}]}, "finishReason": "STOP"}],
             "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2, "thoughtsTokenCount": 3},
             "modelVersion": "gemini-2.5-flash"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Duration> waits = new ArrayList<>();
    private MockRestServiceServer google;
    private RestClient restClient;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test/v1beta");
        google = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    static GeminiProvider properties(int maxRetries) {
        GeminiProvider properties = new GeminiProvider();
        properties.setBaseUrl("https://gemini.test/v1beta");
        properties.setModel("gemini-2.5-flash");
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    private GeminiClient client() {
        return client(KEY);
    }

    private GeminiClient client(String key) {
        return new GeminiClient(restClient, properties(3), () -> key, objectMapper, waits::add, null);
    }

    private static ResponseCreator ok() {
        return withSuccess(OK_BODY, MediaType.APPLICATION_JSON);
    }

    private static ResponseCreator error429(String delay) {
        String details = delay == null
                ? "[]"
                : "[{\"@type\": \"type.googleapis.com/google.rpc.RetryInfo\", \"retryDelay\": \"%s\"}]".formatted(delay);
        return withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": {\"code\": 429, \"status\": \"RESOURCE_EXHAUSTED\", \"details\": %s}}"
                        .formatted(details));
    }

    @Test
    void laLlaveVaEnElEncabezadoYNoEnLaUrl() {
        google.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andRespond(ok());

        GeminiResponse response = client().generate(GeminiRequest.of("Saluda"));

        google.verify(); // requestTo(URL) exige la URL exacta: sin "?key=..."
        assertThat(response.text()).isEqualTo("Hola");
        assertThat(response.inputTokens()).isEqualTo(5);
        assertThat(response.outputTokens()).isEqualTo(5); // 2 de respuesta + 3 de razonamiento
        assertThat(response.model()).isEqualTo("gemini-2.5-flash");
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void reintentaAnteDemasiadasPeticiones() {
        google.expect(requestTo(URL)).andRespond(error429(null));
        google.expect(requestTo(URL)).andRespond(error429(null));
        google.expect(requestTo(URL)).andRespond(ok());

        GeminiResponse response = client().generate(GeminiRequest.of("Saluda"));

        assertThat(response.attempts()).isEqualTo(3);
        assertThat(waits).hasSize(2);
        assertThat(waits.get(0)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(3)); // 2 s + azar
        assertThat(waits.get(1)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5)); // 4 s + azar
    }

    @Test
    void reintentaAnteServicioNoDisponible() {
        google.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        google.expect(requestTo(URL)).andRespond(ok());

        assertThat(client().generate(GeminiRequest.of("Saluda")).attempts()).isEqualTo(2);
    }

    @Test
    void respetaElTiempoQuePideGoogle() {
        google.expect(requestTo(URL)).andRespond(error429("7s"));
        google.expect(requestTo(URL)).andRespond(ok());

        client().generate(GeminiRequest.of("Saluda"));

        assertThat(waits).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void respetaElEncabezadoRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "4");
        google.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(headers));
        google.expect(requestTo(URL)).andRespond(ok());

        client().generate(GeminiRequest.of("Saluda"));

        assertThat(waits).containsExactly(Duration.ofSeconds(4));
    }

    @Test
    void laEsperaTieneUnMaximo() {
        google.expect(requestTo(URL)).andRespond(error429("600s"));
        google.expect(requestTo(URL)).andRespond(ok());

        client().generate(GeminiRequest.of("Saluda"));

        assertThat(waits).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void seRindeAlAgotarLosReintentos() {
        google.expect(times(4), requestTo(URL)).andRespond(error429(null));

        assertThatThrownBy(() -> client().generate(GeminiRequest.of("Saluda")))
                .isInstanceOf(GeminiException.RetriesExhausted.class)
                .hasMessageContaining("4 intentos");

        google.verify(); // 1 intento + 3 reintentos
        assertThat(waits).hasSize(3);
    }

    @Test
    void reintentaAnteFallasDeRed() {
        google.expect(requestTo(URL)).andRespond(withException(new IOException("getaddrinfo failed")));
        google.expect(requestTo(URL)).andRespond(ok());

        assertThat(client().generate(GeminiRequest.of("Saluda")).attempts()).isEqualTo(2);
    }

    @Test
    void unErrorDePeticionNoSeReintenta() {
        google.expect(times(1), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"code\": 400, \"message\": \"API key not valid. Please pass a valid API key.\"}}"));

        assertThatThrownBy(() -> client().generate(GeminiRequest.of("Saluda")))
                .isInstanceOfSatisfying(GeminiException.Rejected.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(400);
                    assertThat(error.getProvider()).isEqualTo("gemini");
                    assertThat(error.getMessage()).contains("API key not valid").doesNotContain(KEY);
                });

        google.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void sinLlaveNoSeLlamaAGoogle() {
        google.expect(never(), requestTo(URL));

        assertThatThrownBy(() -> client("").generate(GeminiRequest.of("Saluda")))
                .isInstanceOf(GeminiException.Configuration.class)
                .hasMessageContaining("GEMINI_API_KEY");

        google.verify();
    }

    @Test
    void enviaInstruccionesYEsquemaJson() throws Exception {
        List<String> bodies = new ArrayList<>();
        google.expect(requestTo(URL))
                .andExpect(request -> bodies.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(ok());
        Map<String, Object> schema = Map.of("type", "OBJECT", "properties", Map.of("score", Map.of("type", "INTEGER")));

        client().generate(GeminiRequest.of("Califica")
                .withSystemInstruction("Eres un analista comercial")
                .withTemperature(0.2)
                .withResponseSchema(schema));

        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("Califica");
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo("Eres un analista comercial");
        assertThat(body.get("generationConfig")).isEqualTo(objectMapper.valueToTree(
                Map.of("temperature", 0.2, "responseMimeType", "application/json", "responseSchema", schema)));
    }

    @Test
    void ignoraElRazonamientoInternoDelModelo() {
        google.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates": [{"content": {"parts": [{"text": "pensando...", "thought": true}, {"text": "Hola"}]},
                                 "finishReason": "STOP"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(client().generate(GeminiRequest.of("Saluda")).text()).isEqualTo("Hola");
    }

    @Test
    void promptBloqueadoPorSeguridad() {
        google.expect(requestTo(URL))
                .andRespond(withSuccess("{\"promptFeedback\": {\"blockReason\": \"SAFETY\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().generate(GeminiRequest.of("Saluda")))
                .isInstanceOf(GeminiException.Blocked.class)
                .hasMessageContaining("SAFETY");
    }

    @Test
    void respuestaVacia() {
        google.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"candidates\": [{\"content\": {\"parts\": []}, \"finishReason\": \"MAX_TOKENS\"}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().generate(GeminiRequest.of("Saluda")))
                .isInstanceOf(GeminiException.InvalidResponse.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    void promptVacio() {
        assertThatThrownBy(() -> client().generate(GeminiRequest.of("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vacío");
    }

    @Test
    void niLasPropiedadesNiLaPeticionMuestranDatosSensibles() {
        GeminiProvider properties = properties(3);
        properties.setApiKey(KEY);

        assertThat(properties.toString()).doesNotContain(KEY);
        assertThat(GeminiRequest.of("Laura, Directora de TI").toString()).doesNotContain("Laura");
    }
}
