package co.com.gtc.prospeccion.crm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Parte de LeafletCompleteProjection (respuesta de register-complete-prospect) que usa este servicio.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeafletCompleteResponseDTO {

    /** pm_leaflet.ID creado. */
    private Integer id;
    /** pm_leafletcontact.ID creado. */
    private Integer contactId;
    private Integer mtStatusId;
    private String mtStatusCode;
}
