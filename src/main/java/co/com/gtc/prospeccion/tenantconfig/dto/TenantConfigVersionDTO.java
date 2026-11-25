package co.com.gtc.prospeccion.tenantconfig.dto;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una fila del historial de configuración (tarea 6.2): quién guardó, cuándo y lo principal de esa versión. El
 * contenido completo se pide aparte (GET /api/config/versions/{id}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantConfigVersionDTO {

    private Integer id;

    /** La vigente (FLGACTIVE = 1). */
    private boolean active;

    private LocalDate createdDate;

    /** Nombre y apellido de quien guardó (o su login si no tiene nombre). */
    private String createdBy;

    /** Campo "version" del perfil de interés; null si el perfil está vacío o no lo trae. */
    private Integer profileVersion;

    /** company.name del perfil. */
    private String companyName;

    private Integer scoreThreshold;

    private Integer dailySendLimit;

    private Integer minSpacingSeconds;

    private boolean hasBaseMessage;
}
