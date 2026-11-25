package co.com.gtc.prospeccion.prospect.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;

class ScoringRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static GeminiQualificationResult raw(int title, int sector, int size, int location, int signals,
            int penalty) {
        Map<String, Integer> criteria = new LinkedHashMap<>();
        criteria.put("title", title);
        criteria.put("sector", sector);
        criteria.put("companySize", size);
        criteria.put("location", location);
        criteria.put("signals", signals);
        return GeminiQualificationResult.builder().criteria(criteria).penalty(penalty).reason("ok")
                .missingData(List.of()).model("gemini-2.5-flash").promptVersion("qualify-v1").build();
    }

    @Test
    void elPerfilAprobadoTieneLasMismasReglasQueLasPorDefecto() throws Exception {
        String json = Files.readString(Path.of("docs/negocio/perfil-de-interes.json"));

        ScoringRules rules = ScoringRules.fromProfile(objectMapper.readTree(json));

        assertThat(rules.criteria()).isEqualTo(ScoringRules.DEFAULT.criteria());
        assertThat(rules.maxPenalty()).isEqualTo(15);
        assertThat(rules.categoryOf(80)).isEqualTo("A");
        assertThat(rules.categoryOf(79)).isEqualTo("B");
        assertThat(rules.categoryOf(39)).isEqualTo("D");
    }

    @Test
    void reproduceLosEjemplosCalificadosDelDocumento() {
        ScoringRules rules = ScoringRules.DEFAULT;

        // docs/negocio/perfil-de-interes.md, "Ejemplos calificados" 1 a 5.
        assertThat(rules.apply(raw(29, 19, 18, 10, 14, 0), 60)).extracting("score", "category", "qualified")
                .containsExactly(90, "A", true);
        assertThat(rules.apply(raw(22, 19, 17, 10, 4, 0), 60).getScore()).isEqualTo(72);
        assertThat(rules.apply(raw(28, 18, 18, 6, 4, 0), 60).getCategory()).isEqualTo("B");
        assertThat(rules.apply(raw(18, 12, 18, 6, 4, 0), 60)).extracting("score", "category", "qualified")
                .containsExactly(58, "C", false);
        assertThat(rules.apply(raw(0, 5, 5, 10, 1, 10), 60)).extracting("score", "category")
                .containsExactly(11, "D");
    }

    @Test
    void recortaLoQueGeminiExagera() {
        GeminiQualificationResult result = ScoringRules.DEFAULT.apply(raw(50, 40, -5, 10, 20, -40), 60);

        assertThat(result.getCriteria()).containsEntry("title", 30).containsEntry("sector", 20)
                .containsEntry("companySize", 0);
        assertThat(result.getPenalty()).isEqualTo(15); // -40 se toma como 40 y se recorta a 15
        assertThat(result.getScore()).isEqualTo(30 + 20 + 0 + 10 + 20 - 15);
    }

    @Test
    void criterioSinRespuestaRecibeLosPuntosDeDatoFaltante() {
        GeminiQualificationResult raw = GeminiQualificationResult.builder()
                .criteria(Map.of("title", 25))
                .missingData(List.of("location", "inventado"))
                .build();

        GeminiQualificationResult result = ScoringRules.DEFAULT.apply(raw, 60);

        assertThat(result.getCriteria()).containsExactly(Map.entry("title", 25), Map.entry("sector", 5),
                Map.entry("companySize", 5), Map.entry("location", 2), Map.entry("signals", 4));
        assertThat(result.getScore()).isEqualTo(41);
        assertThat(result.getPenalty()).isZero();
        // Los criterios sin puntos quedan como faltantes; "inventado" no es un criterio y se descarta.
        assertThat(result.getMissingData()).containsExactly("sector", "companySize", "location", "signals");
    }

    @Test
    void elUmbralDecideSiPasa() {
        assertThat(ScoringRules.DEFAULT.apply(raw(25, 15, 10, 5, 5, 0), 60).getQualified()).isTrue();  // 60
        assertThat(ScoringRules.DEFAULT.apply(raw(25, 15, 10, 5, 5, 0), 61).getQualified()).isFalse();
    }

    @Test
    void perfilSinScoringUsaLosValoresPorDefectoYUnoPropioSeRespeta() throws Exception {
        assertThat(ScoringRules.fromProfile(objectMapper.readTree("{}"))).isSameAs(ScoringRules.DEFAULT);

        ScoringRules custom = ScoringRules.fromProfile(objectMapper.readTree("""
                {"scoring": {"criteria": [{"key": "title", "maxPoints": 100, "missingPoints": 10}],
                 "maxPenalty": 5, "categories": [{"code": "OK", "min": 50, "max": 100}]}}
                """));
        GeminiQualificationResult result = custom.apply(raw(90, 20, 20, 10, 20, 9), 50);

        assertThat(result.getScore()).isEqualTo(85); // solo cuenta "title"; penalización máxima 5
        assertThat(result.getCategory()).isEqualTo("OK");
        assertThat(custom.categoryOf(10)).isNull();
    }

    @Test
    void laRazonSeLimpiaYSeRecortaA300() {
        String larga = "Encaja  muy\nbien. ".repeat(40);

        String reason = ScoringRules.cleanReason(larga);

        assertThat(reason).hasSize(300).endsWith("…").doesNotContain("\n").doesNotContain("  ");
        assertThat(ScoringRules.cleanReason("   ")).isNull();
    }
}
