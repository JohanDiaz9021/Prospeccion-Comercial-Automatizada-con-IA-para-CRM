package co.com.gtc.prospeccion.integration.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.GeminiProvider;
import co.com.gtc.prospeccion.integration.Sleeper;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.integration.gemini.dto.QualificationCandidate;
import co.com.gtc.prospeccion.prospect.scoring.ScoringRules;

/**
 * Prueba contra la API real de Gemini. Solo corre si existe la variable GEMINI_API_KEY (no en CI):
 * <pre>
 * $env:GEMINI_API_KEY = "..."; .\gradlew.bat test --tests "*GeminiClientLiveTest"
 * </pre>
 * Usa solo datos inventados: con la capa gratuita, Google puede usar las solicitudes para mejorar sus productos.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiClientLiveTest {

    @Test
    void respondeJsonConElEsquemaPedido() throws Exception {
        GeminiProvider properties = new GeminiProvider();
        properties.setBaseUrl("https://generativelanguage.googleapis.com/v1beta");
        properties.setModel(System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash"));
        properties.setMaxRetries(2);
        ObjectMapper objectMapper = new ObjectMapper();
        GeminiClient client = new GeminiClient(RestClient.builder().baseUrl(properties.getBaseUrl()).build(),
                properties, () -> System.getenv("GEMINI_API_KEY"), objectMapper, Sleeper.REAL, null);

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of("ciudad", Map.of("type", "STRING"), "cargo", Map.of("type", "STRING")),
                "required", List.of("ciudad", "cargo"));
        GeminiResponse response = client.generate(GeminiRequest
                .of("Extrae la ciudad y el cargo de: 'gerentes de TI en Bogotá'.")
                .withTemperature(0)
                .withResponseSchema(schema));

        JsonNode json = objectMapper.readTree(response.text());
        assertThat(json.get("ciudad").asText()).containsIgnoringCase("Bogot");
        assertThat(json.get("cargo").asText()).isNotBlank();
        assertThat(response.inputTokens()).isPositive();
    }

    /**
     * 3.5 con el perfil aprobado y contactos inventados (ejemplos 1 y 5 de docs/negocio/perfil-de-interes.md):
     * el director de tecnología de una clínica grande en Bogotá debe quedar muy por encima del director creativo
     * de una agencia pequeña.
     */
    @Test
    void calificaContactosInventadosConElPerfilAprobado() throws Exception {
        GeminiClient client = client(new ObjectMapper());
        String profile = Files.readString(Path.of("docs/negocio/perfil-de-interes.json"));
        List<QualificationCandidate> contacts = List.of(
                new QualificationCandidate("1", "Director de Tecnología", "Clínica Ficticia del Norte", null,
                        List.of("Bogota, Colombia"), null, List.of("hospital & health care"), List.of("201,500"),
                        null, List.of("La empresa usa SAP")),
                new QualificationCandidate("2", "Director Creativo", "Agencia Ficticia Creativa", null,
                        List.of("Bogota, Colombia"), null, List.of("marketing & advertising"), List.of("11,50"),
                        null, null));

        List<GeminiQualificationResult> raw = client.qualifyContacts(profile, contacts);

        assertThat(raw).doesNotContainNull();
        ScoringRules rules = ScoringRules.fromProfile(new ObjectMapper().readTree(profile));
        GeminiQualificationResult good = rules.apply(raw.get(0), 60);
        GeminiQualificationResult bad = rules.apply(raw.get(1), 60);
        System.out.printf("Gemini (%s, %s): %d %s / %d %s%n  %s%n  %s%n", good.getModel(), good.getPromptVersion(),
                good.getScore(), good.getCategory(), bad.getScore(), bad.getCategory(), good.getReason(),
                bad.getReason());
        assertThat(good.getScore()).isGreaterThanOrEqualTo(70);
        assertThat(bad.getScore()).isLessThan(40);
        assertThat(good.getReason()).isNotBlank().hasSizeLessThanOrEqualTo(300);
    }

    private static GeminiClient client(ObjectMapper objectMapper) {
        GeminiProvider properties = new GeminiProvider();
        properties.setBaseUrl("https://generativelanguage.googleapis.com/v1beta");
        properties.setModel(System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash"));
        properties.setMaxRetries(2);
        return new GeminiClient(RestClient.builder().baseUrl(properties.getBaseUrl()).build(), properties,
                () -> System.getenv("GEMINI_API_KEY"), objectMapper, Sleeper.REAL, new PromptLoader());
    }
}
