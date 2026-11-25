package co.com.gtc.prospeccion.prospect.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prueba de calificación (3.5) con contactos escritos a mano: sirve para ajustar el perfil de interés antes de
 * guardarlo. No guarda nada. Usar solo datos inventados (con la capa gratuita, Google puede usar las solicitudes).
 */
@Data
@NoArgsConstructor
public class QualificationPreviewRequest {

    /** Perfil a probar sin guardarlo. Si viene vacío se usa el de la configuración vigente. */
    @Size(max = 20000)
    private String profileOfInterest;

    /** Umbral a probar. Si viene vacío se usa el de la configuración vigente (o 60). */
    @Min(0)
    @Max(100)
    private Integer scoreThreshold;

    @NotEmpty
    @Size(max = 10)
    @Valid
    private List<Contact> contacts;

    @Data
    @NoArgsConstructor
    public static class Contact {
        @Size(max = 200)
        private String title;
        @Size(max = 200)
        private String organizationName;
        @Size(max = 20)
        private List<@Size(max = 100) String> seniorities;
        @Size(max = 20)
        private List<@Size(max = 100) String> personLocations;
        @Size(max = 20)
        private List<@Size(max = 100) String> organizationLocations;
        @Size(max = 20)
        private List<@Size(max = 100) String> industries;
        @Size(max = 20)
        private List<@Size(max = 30) String> employeeRanges;
        @Size(max = 200)
        private String keywords;
        @Size(max = 10)
        private List<@Size(max = 300) String> signals;
    }
}
