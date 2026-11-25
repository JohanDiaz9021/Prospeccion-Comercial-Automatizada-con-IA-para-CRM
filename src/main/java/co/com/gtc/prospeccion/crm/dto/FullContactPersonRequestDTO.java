package co.com.gtc.prospeccion.crm.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de FullContactPersonRequestDTO de msa-cotizaciones: agrega una persona (y sus medios)
 * a un pm_leaflet que ya existe. POST /msa-cotizaciones/api/leafletContact/personContact?userName=...
 * contact.leafletET debe traer el pm_leaflet.ID.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullContactPersonRequestDTO {

    private LeafletContactRequestDTO contact;
    private List<LeafletContactMediumRequestDTO> contactMediumList;
}
