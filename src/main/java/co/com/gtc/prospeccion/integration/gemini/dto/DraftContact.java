package co.com.gtc.prospeccion.integration.gemini.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lo que Gemini ve del contacto para redactar el borrador (tarea 4.3). Solo datos con evidencia (la_evidence);
 * lo que no se sabe va en null y no viaja. No lleva apellido, correo ni IDs: no hacen falta para redactar.
 *
 * @param firstName        primer nombre
 * @param title            cargo
 * @param organizationName empresa
 * @param industry         sector según Apollo (en inglés)
 * @param companySize      número o rango de empleados
 * @param city             ciudad de la persona o de la empresa
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DraftContact(
        String firstName,
        String title,
        String organizationName,
        String industry,
        String companySize,
        String city) {
}
