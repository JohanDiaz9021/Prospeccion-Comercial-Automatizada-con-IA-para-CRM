package co.com.gtc.prospeccion.tenantconfig.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuración editable del robot para el tenant (la_tenantconfig vigente).
 * MAILBOX_SSM_KEY no se expone: lo carga TI y se conserva entre versiones.
 */
@Data
@NoArgsConstructor
public class TenantConfigDTO {

    /**
     * ID de la versión vigente. En el PUT se envía el ID que se estaba editando: si otra persona guardó una versión
     * más nueva, responde 409. Null cuando el tenant aún no tiene configuración.
     */
    private Integer id;

    /** JSON con el formato de docs/negocio/perfil-de-interes.md (unos 8.000 caracteres en v1). */
    @Size(max = 20000)
    private String profileOfInterest;

    /** Texto con el formato de docs/negocio/mensaje-base.md. */
    @Size(max = 5000)
    private String baseMessage;

    /** Puntaje mínimo (0-100) para gastar créditos y redactar. Valor inicial del perfil aprobado: 60. */
    @Min(0)
    @Max(100)
    private Integer scoreThreshold;

    /** Correos máximos por día y por buzón. */
    @Min(0)
    private Integer dailySendLimit;

    /** Segundos mínimos entre dos envíos del mismo buzón. */
    @Min(0)
    private Integer minSpacingSeconds;

    @Size(max = 500)
    @Pattern(regexp = "^https://\\S+$", message = "debe empezar por https://")
    private String companyLinkedinUrl;
}
