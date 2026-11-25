package co.com.gtc.prospeccion.integration.gemini.dto;

import java.util.List;

/**
 * Filtros de Apollo tal como los propone Gemini a partir de la búsqueda en lenguaje natural (tarea 3.2). Todavía sin
 * limpiar: SearchInterpretService quita niveles y rangos que Apollo no conoce, repetidos y vacíos.
 *
 * @param understood false si el texto no describe a quién buscar
 */
public record GeminiSearchFilters(
        boolean understood,
        List<String> personTitles,
        List<String> personSeniorities,
        List<String> organizationLocations,
        List<String> organizationIndustries,
        List<String> organizationNumEmployeesRanges,
        List<String> keywords,
        String model,
        String promptVersion) {
}
