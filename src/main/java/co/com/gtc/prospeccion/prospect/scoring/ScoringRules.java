package co.com.gtc.prospeccion.prospect.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;

/**
 * Reglas de puntaje del perfil de interés ({@code scoring}, docs/negocio/perfil-de-interes.md sección 7).
 * <p>
 * Gemini solo propone los puntos de cada criterio. Aquí se aplican las reglas que no se le dejan a la IA: cada
 * criterio queda entre 0 y su máximo, un criterio sin respuesta recibe los puntos de "dato faltante", la
 * penalización no pasa del máximo, el total queda entre 0 y 100 y la categoría sale de la tabla del perfil.
 */
public final class ScoringRules {

    public static final int REASON_MAX_LENGTH = 300;

    public record Criterion(String key, int maxPoints, int missingPoints) {
    }

    public record Category(String code, int min, int max) {
    }

    /** Valores aprobados en el perfil v1, para un perfil que no traiga {@code scoring}. */
    public static final ScoringRules DEFAULT = new ScoringRules(
            List.of(new Criterion("title", 30, 7), new Criterion("sector", 20, 5),
                    new Criterion("companySize", 20, 5), new Criterion("location", 10, 2),
                    new Criterion("signals", 20, 4)),
            15,
            List.of(new Category("A", 80, 100), new Category("B", 60, 79), new Category("C", 40, 59),
                    new Category("D", 0, 39)));

    private final List<Criterion> criteria;
    private final int maxPenalty;
    private final List<Category> categories;

    public ScoringRules(List<Criterion> criteria, int maxPenalty, List<Category> categories) {
        this.criteria = List.copyOf(criteria);
        this.maxPenalty = Math.max(0, maxPenalty);
        this.categories = List.copyOf(categories);
    }

    /** Lee {@code scoring} del perfil; cada parte que falte o esté mal formada toma el valor por defecto. */
    public static ScoringRules fromProfile(JsonNode profile) {
        JsonNode scoring = profile == null ? null : profile.path("scoring");
        if (scoring == null || !scoring.isObject()) {
            return DEFAULT;
        }

        List<Criterion> criteria = new ArrayList<>();
        for (JsonNode node : scoring.path("criteria")) {
            String key = node.path("key").asText("");
            if (!key.isBlank() && node.path("maxPoints").isInt() && node.path("missingPoints").isInt()) {
                criteria.add(new Criterion(key, Math.max(0, node.path("maxPoints").asInt()),
                        Math.max(0, node.path("missingPoints").asInt())));
            }
        }
        List<Category> categories = new ArrayList<>();
        for (JsonNode node : scoring.path("categories")) {
            String code = node.path("code").asText("");
            if (!code.isBlank() && node.path("min").isInt() && node.path("max").isInt()) {
                categories.add(new Category(code, node.path("min").asInt(), node.path("max").asInt()));
            }
        }
        JsonNode penalty = scoring.path("maxPenalty");
        return new ScoringRules(
                criteria.isEmpty() ? DEFAULT.criteria : criteria,
                penalty.isInt() ? penalty.asInt() : DEFAULT.maxPenalty,
                categories.isEmpty() ? DEFAULT.categories : categories);
    }

    /**
     * Normaliza lo que dio Gemini y calcula puntaje, categoría y si pasa el umbral. No modifica {@code raw}.
     */
    public GeminiQualificationResult apply(GeminiQualificationResult raw, int threshold) {
        Map<String, Integer> given = raw.getCriteria() == null ? Map.of() : raw.getCriteria();
        Set<String> declaredMissing = raw.getMissingData() == null ? Set.of() : Set.copyOf(raw.getMissingData());

        Map<String, Integer> points = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        int total = 0;
        for (Criterion criterion : criteria) {
            Integer value = given.get(criterion.key());
            if (value == null) {
                value = criterion.missingPoints();
                missing.add(criterion.key());
            } else if (declaredMissing.contains(criterion.key())) {
                missing.add(criterion.key());
            }
            value = clamp(value, 0, criterion.maxPoints());
            points.put(criterion.key(), value);
            total += value;
        }
        int penalty = clamp(raw.getPenalty() == null ? 0 : Math.abs(raw.getPenalty()), 0, maxPenalty);
        int score = clamp(total - penalty, 0, 100);

        return GeminiQualificationResult.builder()
                .score(score)
                .category(categoryOf(score))
                .criteria(points)
                .penalty(penalty)
                .reason(cleanReason(raw.getReason()))
                .missingData(List.copyOf(missing))
                .qualified(score >= threshold)
                .model(raw.getModel())
                .promptVersion(raw.getPromptVersion())
                .build();
    }

    public String categoryOf(int score) {
        return categories.stream()
                .filter(category -> score >= category.min() && score <= category.max())
                .map(Category::code)
                .findFirst()
                .orElse(null);
    }

    public List<Criterion> criteria() {
        return criteria;
    }

    public int maxPenalty() {
        return maxPenalty;
    }

    static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String clean = reason.strip().replaceAll("\\s+", " ");
        return clean.length() <= REASON_MAX_LENGTH ? clean : clean.substring(0, REASON_MAX_LENGTH - 1).strip() + "…";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
