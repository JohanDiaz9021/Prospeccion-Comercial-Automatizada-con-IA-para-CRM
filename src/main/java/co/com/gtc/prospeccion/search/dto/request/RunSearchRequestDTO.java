package co.com.gtc.prospeccion.search.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filtros (revisados por el usuario) con los que se ejecuta o previsualiza la búsqueda.
 */
@Data
@NoArgsConstructor
public class RunSearchRequestDTO {

    @Size(max = 1000)
    private String naturalQuery;

    @NotNull
    @Valid
    private SearchFiltersResponseDTO filters;

    // referencia a pm_listcontact, la gestiona msa-cotizaciones
    private Integer pmListContactId;

    /** Campaña: además de ejecutarse ahora, se repite sola (ScheduledSearchJob). */
    private Boolean scheduled;

    /** Con scheduled: DAILY (cada día hábil, por defecto) o WEEKLY (una vez por semana). */
    @Pattern(regexp = "DAILY|WEEKLY")
    private String frequency;
}
