package co.com.gtc.prospeccion.crm.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Espejo de FullProspectRequestDTO de msa-cotizaciones.
 * POST /msa-cotizaciones/api/leaflet/register-complete-prospect-by-user-id?userId=...
 * Crea pm_leaflet + pm_leafletcontact + pm_leafletcontactmedium en una sola transacción.
 * Detalle: docs/crm/peticion-completa-y-dominio-de-prospectos.md
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullProspectRequestDTO {

    /** Obligatorio. */
    private LeafletRequestDTO leaflet;

    /** Obligatorio. msa-cotizaciones le asigna el ID del pm_leaflet creado. */
    private LeafletContactRequestDTO contact;

    /** Correo y teléfono del contacto. msa-cotizaciones les asigna el pm_leaflet y el pm_leafletcontact. */
    private List<LeafletContactMediumRequestDTO> contactMediumList;

    /** IDs de pm_listcontact a las que se asocia el contacto (opcional). */
    private List<Integer> contactListIds;
}
