package co.com.gtc.prospeccion.draft.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prueba de redacción (4.3) con un contacto escrito a mano: sirve para ajustar el mensaje base antes de guardarlo.
 * No guarda nada. Usar solo datos inventados (con la capa gratuita, Google puede usar las solicitudes).
 */
@Data
@NoArgsConstructor
public class DraftPreviewRequestDTO {

    /** Mensaje base a probar sin guardarlo. Si viene vacío se usa el de la configuración vigente. */
    @Size(max = 5000)
    private String baseMessage;

    @NotNull
    @Valid
    private Contact contact;

    @Data
    @NoArgsConstructor
    public static class Contact {
        @Size(max = 100)
        private String firstName;
        @Size(max = 200)
        private String title;
        @Size(max = 200)
        private String organizationName;
        @Size(max = 150)
        private String industry;
        @Size(max = 30)
        private String companySize;
        @Size(max = 150)
        private String city;
    }
}
