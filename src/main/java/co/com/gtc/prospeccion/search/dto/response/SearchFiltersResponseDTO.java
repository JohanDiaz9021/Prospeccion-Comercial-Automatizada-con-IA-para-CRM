package co.com.gtc.prospeccion.search.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filtros de Apollo propuestos por Gemini; el usuario puede ajustarlos antes de ejecutar. Antes de usarlos se limpian
 * con SearchFilterRules (niveles y rangos válidos, sin repetidos ni vacíos).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchFiltersResponseDTO {

    /** Cargos exactos, en español y en inglés. Apollo acepta menos de 100. */
    @Size(max = 60)
    private List<String> personTitles;
    /** owner, founder, c_suite, partner, vp, head, director, manager, senior, entry, intern. */
    @Size(max = 11)
    private List<String> personSeniorities;
    /** Sede de la empresa, formato de Apollo: "Bogota, Colombia". */
    @Size(max = 30)
    private List<String> organizationLocations;
    /** Sectores de Apollo en minúsculas: "hospital & health care". */
    @Size(max = 30)
    private List<String> organizationIndustries;
    /** "51,200", "201,500"... (sy_mastertable 606). */
    @Size(max = 8)
    private List<String> organizationNumEmployeesRanges;
    /** Palabras clave (q_keywords de Apollo). */
    @Size(max = 5)
    private List<String> keywords;
    /** Modelo que interpretó la búsqueda (solo en la respuesta de /interpret). */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String model;
}
