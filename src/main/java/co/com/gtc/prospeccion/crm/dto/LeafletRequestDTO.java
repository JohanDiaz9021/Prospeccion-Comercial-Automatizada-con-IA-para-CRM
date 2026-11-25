package co.com.gtc.prospeccion.crm.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de LeafletRequestDTO de msa-cotizaciones (tabla pm_leaflet).
 * Los campos mt* llevan sy_mastertable.ID (no el CODE). La auditoría (fechas y usuario) la llena msa-cotizaciones.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeafletRequestDTO {

    /** sy_mastertable TABLECODE 35. Opcional: Apollo no entrega NIT. */
    private Integer mtIdentificationTypeId;
    /** Opcional. Si viene, msa-cotizaciones valida que no se repita con el mismo tipo. */
    private String identificationNumber;
    /** Obligatorio. */
    private String firstName;
    private String middleName;
    private String surname;
    private String middleSurName;
    /** msa-cotizaciones lo busca en sy_datetable como texto. No se usa en Prospección IA. */
    private Integer birthdate;
    /** Obligatorio. sy_mastertable TABLECODE 221 (CODE "1" = nuevo). */
    private Integer mtStatusId;
    private Integer createdDate;
    private Integer lastUpdateDate;
    private String updatedBy;
    private Integer createdBy;
    private String interest;
    private Boolean flgDataAuthorization;
    /** sy_mastertable.ID del canal de adquisición (TABLECODE por confirmar con el equipo CRM). */
    private Integer acquisitionChannel;
    /** pv_provider.ID del responsable comercial (opcional). */
    private Integer commercialManagerId;
    /** ID de la ciudad en la tabla DANE del CRM (opcional). */
    private Integer caDaneId;
    private List<Integer> contactsLeaflet;
    private List<Integer> contactsMediumLeaflet;
}
