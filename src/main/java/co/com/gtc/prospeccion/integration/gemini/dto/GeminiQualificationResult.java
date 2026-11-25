package co.com.gtc.prospeccion.integration.gemini.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Calificación de un contacto (tarea 3.5).
 * <p>
 * GeminiClient llena {@code criteria}, {@code penalty}, {@code reason} y {@code missingData} tal como los da Gemini.
 * QualificationService los normaliza con el perfil del tenant (recorta cada criterio a su máximo), calcula
 * {@code score} y {@code category} en el código y decide {@code qualified} con el umbral. Se guardan en la_contactext
 * SCORE, SCORE_REASON, MODEL y PROMPT_VERSION; el resto sirve para la pantalla y las pruebas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiQualificationResult {

    /** Puntaje final de 0 a 100 (suma de criterios menos penalización), calculado en el código. */
    private Integer score;
    /** A, B, C o D según scoring.categories del perfil. */
    private String category;
    /** Puntos por criterio: title, sector, companySize, location, signals. */
    private Map<String, Integer> criteria;
    /** Puntos restados por señales negativas (0 a maxPenalty). */
    private Integer penalty;
    /** Justificación en español para el revisor (máximo 300 caracteres). */
    private String reason;
    /** Criterios que se calificaron sin dato. */
    private List<String> missingData;
    /** true si el puntaje alcanza el umbral del tenant: se gastan créditos para conseguir el correo. */
    private Boolean qualified;
    /** Si no se pudo calificar (BLOCKED, INVALID_RESPONSE): score es null y se puede reintentar después. */
    private String error;
    private String model;
    private String promptVersion;
}
