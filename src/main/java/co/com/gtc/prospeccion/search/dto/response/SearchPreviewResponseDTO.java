package co.com.gtc.prospeccion.search.dto.response;

import java.util.List;

/**
 * Vista previa de una búsqueda (tarea 3.2): cuántas personas trae Apollo con los filtros y una muestra de la primera
 * página. No gasta créditos ni califica: sirve para ajustar los filtros antes de ejecutar.
 *
 * @param totalEntries total que reporta Apollo
 * @param newInSample  de la muestra, cuántas nunca se habían procesado
 * @param filters      filtros limpios con los que se buscó
 * @param sample       primeras personas, sin apellido completo ni correo (Apollo no los da al buscar)
 */
public record SearchPreviewResponseDTO(int totalEntries, int newInSample, SearchFiltersResponseDTO filters,
        List<Person> sample) {

    public record Person(String firstName, String lastNameObfuscated, String title, String organizationName,
            Boolean hasEmail, boolean alreadySeen) {
    }
}
