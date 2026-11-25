package co.com.gtc.prospeccion.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de LeafletContactMediumRequestDTO de msa-cotizaciones (tabla pm_leafletcontactmedium).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeafletContactMediumRequestDTO {

    /** sy_mastertable.ID de TABLECODE 10: CODE "5" = correo, CODE "1" = teléfono. */
    private Integer mtContactProviderETId;
    /** Lo asigna msa-cotizaciones. */
    private Integer leafletET;
    private String value;
    /** Lo asigna msa-cotizaciones. */
    private Integer contactLeafletET;
    private Integer createdDate;
    private Integer lastUpdateDate;
    private String updateBy;
    private Integer createdBy;
}
