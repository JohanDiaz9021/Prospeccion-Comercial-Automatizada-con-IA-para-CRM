package co.com.gtc.prospeccion.integration.gemini.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lo que Gemini ve de un contacto para calificarlo (tarea 3.5). Solo datos de negocio: no lleva nombre, apellido,
 * correo ni el ID de Apollo (minimización de datos; con la capa gratuita Google puede usar las solicitudes).
 * <p>
 * People API Search no devuelve sector, tamaño ni ubicación, solo banderas has_*. Por eso los campos
 * {@code possible*} salen de los filtros de la búsqueda: el contacto cumple al menos uno de esos valores.
 *
 * @param ref                           identificador dentro de la petición ("1", "2"...), lo devuelve Gemini
 * @param title                         cargo
 * @param organizationName              nombre de la empresa
 * @param possibleSeniorities           niveles de la búsqueda (person_seniorities)
 * @param possiblePersonLocations       ubicaciones de la persona (person_locations)
 * @param possibleOrganizationLocations sede de la empresa (organization_locations)
 * @param possibleIndustries            sectores en nombres de Apollo (organization_industries)
 * @param possibleEmployeeRanges        rangos de empleados (organization_num_employees_ranges)
 * @param keywords                      palabras clave exigidas por la búsqueda (q_keywords)
 * @param signals                       hechos confirmados (por ejemplo tecnologías), cuando existan
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record QualificationCandidate(
        String ref,
        String title,
        String organizationName,
        List<String> possibleSeniorities,
        List<String> possiblePersonLocations,
        List<String> possibleOrganizationLocations,
        List<String> possibleIndustries,
        List<String> possibleEmployeeRanges,
        String keywords,
        List<String> signals) {

    /** No imprime los datos: aunque son de negocio, van con el cargo de una persona real. */
    @Override
    public String toString() {
        return "QualificationCandidate[ref=" + ref + "]";
    }
}
