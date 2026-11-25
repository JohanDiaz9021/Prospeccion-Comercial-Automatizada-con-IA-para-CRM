package co.com.gtc.prospeccion.search.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;

/**
 * Reglas de los filtros de búsqueda (tarea 3.2). Lo que propone Gemini o escribe el usuario pasa por aquí antes de
 * mostrarse y antes de llegar a Apollo:
 * <ul>
 * <li>Niveles de cargo y rangos de empleados: solo los que Apollo conoce (sy_mastertable 605 y 606).</li>
 * <li>Sin vacíos ni repetidos (sin importar mayúsculas), cada valor de hasta 100 caracteres y topes por lista.</li>
 * <li>Sin ubicación se busca en "Colombia": nunca se prospecta fuera del país.</li>
 * </ul>
 */
public final class SearchFilterRules {

    public static final List<String> SENIORITIES = List.of("owner", "founder", "c_suite", "partner", "vp", "head",
            "director", "manager", "senior", "entry", "intern");
    public static final List<String> EMPLOYEE_RANGES = List.of("1,10", "11,50", "51,200", "201,500", "501,1000",
            "1001,5000", "5001,10000", "10001,");
    public static final String DEFAULT_LOCATION = "Colombia";

    static final int MAX_TITLES = 60;
    static final int MAX_LIST = 30;
    static final int MAX_KEYWORDS = 5;
    static final int MAX_VALUE_LENGTH = 100;

    private SearchFilterRules() {
    }

    /** Filtros limpios. Nunca devuelve null en las listas. */
    public static SearchFiltersResponseDTO clean(SearchFiltersResponseDTO filters) {
        SearchFiltersResponseDTO f = filters == null ? new SearchFiltersResponseDTO() : filters;
        List<String> locations = distinct(f.getOrganizationLocations(), MAX_LIST, false);
        return SearchFiltersResponseDTO.builder()
                .personTitles(distinct(f.getPersonTitles(), MAX_TITLES, false))
                .personSeniorities(allowed(f.getPersonSeniorities(), SENIORITIES))
                .organizationLocations(locations.isEmpty() ? List.of(DEFAULT_LOCATION) : locations)
                .organizationIndustries(distinct(f.getOrganizationIndustries(), MAX_LIST, true))
                .organizationNumEmployeesRanges(allowed(f.getOrganizationNumEmployeesRanges(), EMPLOYEE_RANGES))
                .keywords(distinct(f.getKeywords(), MAX_KEYWORDS, false))
                .model(f.getModel())
                .build();
    }

    /**
     * true si los filtros limpios dicen a quién buscar: un cargo, un nivel, un sector o una palabra clave. Solo la
     * ubicación o solo el tamaño traerían a cualquier persona de cualquier empresa.
     */
    public static boolean saysWhoToFind(SearchFiltersResponseDTO clean) {
        return !clean.getPersonTitles().isEmpty() || !clean.getPersonSeniorities().isEmpty()
                || !clean.getOrganizationIndustries().isEmpty() || !clean.getKeywords().isEmpty();
    }

    /**
     * Petición de People API Search con los filtros limpios.
     *
     * @param excludedDomains dominios de empresas que no se buscan (competidores y excluidas a mano)
     */
    public static ApolloPeopleSearchRequest toApollo(SearchFiltersResponseDTO clean, Collection<String> excludedDomains) {
        return ApolloPeopleSearchRequest.builder()
                .personTitles(nullIfEmpty(clean.getPersonTitles()))
                .personSeniorities(nullIfEmpty(clean.getPersonSeniorities()))
                .organizationLocations(nullIfEmpty(clean.getOrganizationLocations()))
                .organizationIndustries(nullIfEmpty(clean.getOrganizationIndustries()))
                .organizationNumEmployeesRanges(nullIfEmpty(clean.getOrganizationNumEmployeesRanges()))
                .notOrganizationWebsitesList(excludedDomains == null || excludedDomains.isEmpty()
                        ? null : List.copyOf(excludedDomains))
                .qKeywords(clean.getKeywords().isEmpty() ? null : String.join(" ", clean.getKeywords()))
                .build();
    }

    private static List<String> allowed(List<String> values, List<String> valid) {
        Set<String> validSet = Set.copyOf(valid);
        List<String> result = new ArrayList<>();
        for (String value : distinct(values, valid.size(), true)) {
            String normalized = value.replace(" ", "");
            if (validSet.contains(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        // Mismo orden del catálogo, para que la misma búsqueda produzca siempre el mismo JSON.
        result.sort((a, b) -> Integer.compare(valid.indexOf(a), valid.indexOf(b)));
        return result;
    }

    private static List<String> distinct(List<String> values, int max, boolean lowerCase) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String v = value.strip().replaceAll("\\s+", " ");
                if (v.isEmpty() || v.length() > MAX_VALUE_LENGTH) {
                    continue;
                }
                if (lowerCase) {
                    v = v.toLowerCase(Locale.ROOT);
                }
                unique.putIfAbsent(v.toLowerCase(Locale.ROOT), v);
                if (unique.size() == max) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static List<String> nullIfEmpty(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }
}
