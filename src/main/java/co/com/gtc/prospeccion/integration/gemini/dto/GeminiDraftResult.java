package co.com.gtc.prospeccion.integration.gemini.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Borrador de correo tal como lo devuelve Gemini (tarea 4.3). El HTML todavía no está limpio: DraftService lo pasa
 * por HtmlSanitizerUtil, lo revisa con DraftRules y saca la versión en texto. Una persona lo aprueba antes de enviarlo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiDraftResult {

    private String subject;
    private String bodyHtml;
    private String model;
    private String promptVersion;
}
