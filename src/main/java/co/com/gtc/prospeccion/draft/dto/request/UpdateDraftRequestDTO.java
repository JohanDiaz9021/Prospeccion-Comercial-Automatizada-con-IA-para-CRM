package co.com.gtc.prospeccion.draft.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Edición humana del borrador. El HTML se limpia con HtmlSanitizerUtil antes de guardarse.
 */
@Data
@NoArgsConstructor
public class UpdateDraftRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String subject;

    /** HTML; se limpia con HtmlSanitizerUtil antes de guardarse. */
    @NotBlank
    @Size(max = 20000)
    private String body;

    /** Versión que el usuario editó, para detectar cambios concurrentes. */
    @NotNull
    private Integer version;
}
