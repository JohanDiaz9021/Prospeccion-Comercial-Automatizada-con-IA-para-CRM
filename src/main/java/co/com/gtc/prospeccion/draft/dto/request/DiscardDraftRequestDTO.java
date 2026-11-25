package co.com.gtc.prospeccion.draft.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DiscardDraftRequestDTO {

    @NotBlank
    @Size(max = 500)
    private String reason;
}
