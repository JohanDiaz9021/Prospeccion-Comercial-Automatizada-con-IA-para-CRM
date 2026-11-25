package co.com.gtc.prospeccion.integration.hunter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Email Finder (GET /v2/email-finder), contenido de "data". Cobra 1 búsqueda solo si encuentra el correo.
 * No registrar en logs: contiene datos personales.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HunterEmailFinderResponse {

    private String email;
    /** Probabilidad de 0 a 100 de que el correo sea el correcto. */
    private Integer score;
    private String domain;
    private Boolean acceptAll;
    private String position;
    private Verification verification;

    /** Encabezado X-Credits-Used (para el registro de créditos, tarea 4.2). */
    @JsonIgnore
    private Double creditsUsed;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Verification {
        /** valid, accept_all o unknown. */
        private String status;
        private String date;
    }

    /** Estado de verificación que trae el propio finder, o null. */
    public String verificationStatus() {
        return verification == null ? null : verification.getStatus();
    }
}
