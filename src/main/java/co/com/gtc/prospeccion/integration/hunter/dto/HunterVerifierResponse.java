package co.com.gtc.prospeccion.integration.hunter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Email Verifier (GET /v2/email-verifier), contenido de "data". Cuesta 0,5 crédito (cupo de verificaciones).
 * No registrar en logs: contiene datos personales.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HunterVerifierResponse {

    public static final String VALID = "valid";
    public static final String INVALID = "invalid";
    public static final String ACCEPT_ALL = "accept_all";
    public static final String WEBMAIL = "webmail";
    public static final String DISPOSABLE = "disposable";
    public static final String UNKNOWN = "unknown";

    private String email;
    /** valid, invalid, accept_all, webmail, disposable o unknown. */
    private String status;
    /** Obsoleto en Hunter (usar status): deliverable, undeliverable o risky. */
    private String result;
    private Integer score;
    private Boolean acceptAll;
    private Boolean webmail;
    private Boolean disposable;
    /** El servidor de correo no dejó hacer la verificación. */
    private Boolean block;

    /** Encabezado X-Credits-Used (para el registro de créditos, tarea 4.2). */
    @JsonIgnore
    private Double creditsUsed;
}
