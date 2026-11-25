package co.com.gtc.prospeccion.integration.apollo.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filtros de People API Search (POST /mixed_people/api_search). Los produce Gemini a partir de la búsqueda en
 * lenguaje natural y el usuario los ajusta. Nombres según la documentación oficial de Apollo; viajan como query
 * params (las listas con sufijo []). La paginación la maneja ApolloClient.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApolloPeopleSearchRequest {

    /** person_titles[]: basta con coincidir con uno. Junto con personNotTitles, menos de 100 cargos. */
    private List<String> personTitles;
    /** include_similar_titles: false = solo coincidencias estrictas. Por defecto Apollo usa true. */
    private Boolean includeSimilarTitles;
    /** person_not_titles[] */
    private List<String> personNotTitles;
    /** person_seniorities[]: owner, founder, c_suite, partner, vp, head, director, manager, senior, entry, intern. */
    private List<String> personSeniorities;
    /** person_locations[]: donde vive la persona (por ejemplo "Bogota, Colombia"). */
    private List<String> personLocations;
    /** person_not_locations[] */
    private List<String> personNotLocations;
    /** organization_locations[]: sede principal de la empresa. */
    private List<String> organizationLocations;
    /** organization_industries[]: nombres exactos de Apollo, en minúsculas (por ejemplo "hospital & health care"). */
    private List<String> organizationIndustries;
    /** organization_num_employees_ranges[]: "51,200", "201,500"... */
    private List<String> organizationNumEmployeesRanges;
    /** not_organization_websites_list[]: dominios a excluir (competidores, clientes, ya contactados). */
    private List<String> notOrganizationWebsitesList;
    /** contact_email_status[]: verified, unverified, likely to engage, unavailable. */
    private List<String> contactEmailStatus;
    /** q_keywords */
    private String qKeywords;
}
