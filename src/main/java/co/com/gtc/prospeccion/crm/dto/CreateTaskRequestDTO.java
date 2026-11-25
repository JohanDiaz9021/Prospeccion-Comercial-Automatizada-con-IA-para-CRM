package co.com.gtc.prospeccion.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de RequestCreateTask de msa-cotizaciones (tabla pm_task).
 * POST /msa-cotizaciones/api/task/create-by-user-id?userId=...
 * Si viene leafletId, msa-cotizaciones crea también pm_taskleaflet en la misma llamada.
 * El estado lo fija msa-cotizaciones: TABLECODE 251, CODE "1" (pendiente).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequestDTO {

    /** Obligatorio. sy_mastertable.ID de TABLECODE 246 (tipo de tarea). */
    private Integer mtTaskTypeId;
    /** Obligatorio. */
    private String name;
    /** Obligatorio. Se busca en sy_datetable.DATECHAR (formato por confirmar con el equipo CRM). */
    private String expirationDate;
    /** Obligatorio. */
    private String expirationHour;
    /** sy_mastertable.ID de TABLECODE 248. */
    private Integer mtPeriodicity;
    private Boolean flgRemember;
    private Integer duration;
    /** sy_mastertable.ID de TABLECODE 247. */
    private Integer mtPriority;
    /** msa-cotizaciones lo ignora al crear. */
    private Integer mtStatus;
    /** pm_leaflet.ID: asocia la tarea al prospecto (pm_taskleaflet). */
    private Integer leafletId;
    private Integer clientId;
    private String observation;
}
