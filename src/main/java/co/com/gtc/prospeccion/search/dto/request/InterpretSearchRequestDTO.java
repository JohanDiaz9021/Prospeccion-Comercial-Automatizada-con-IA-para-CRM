package co.com.gtc.prospeccion.search.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Búsqueda en lenguaje natural escrita por el usuario.
 */
@Data
@NoArgsConstructor
public class InterpretSearchRequestDTO {

    @NotBlank
    @Size(max = 1000)
    private String naturalQuery;
}
