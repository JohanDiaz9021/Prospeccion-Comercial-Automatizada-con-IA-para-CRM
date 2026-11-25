package co.com.gtc.prospeccion.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de LeafletContactRequestDTO de msa-cotizaciones (tabla pm_leafletcontact).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeafletContactRequestDTO {

    /** pm_leaflet.ID. En register-complete-prospect lo asigna msa-cotizaciones. */
    private Integer leafletET;
    /** 1 = contacto principal del prospecto; 0 = persona de contacto adicional. */
    private Integer userFlg;
    /** sy_mastertable TABLECODE 35 (opcional). */
    private Integer mtIdentificationTypeId;
    private String identification;
    /** Cargo de la persona (title de Apollo). */
    private String position;
    private String name;
    private String middleName;
    private String surname;
    private String middleSurname;
    private Integer birthDate;
    private Integer createdDate;
    private Integer lastUpdateDate;
    private String updateBy;
    private Integer createdBy;
}
