package co.com.gtc.prospeccion.integration.gemini;

import java.util.Map;

/**
 * Lo que se le pide a Gemini.
 *
 * @param prompt            texto de la petición (obligatorio)
 * @param systemInstruction instrucción de sistema (opcional)
 * @param temperature       opcional; bajo (0 a 0.3) para respuestas estables
 * @param responseSchema    opcional; con él Gemini responde JSON que cumple el esquema (formato OpenAPI de Gemini)
 * @param maxOutputTokens   opcional
 */
public record GeminiRequest(
        String prompt,
        String systemInstruction,
        Double temperature,
        Map<String, Object> responseSchema,
        Integer maxOutputTokens) {

    public static GeminiRequest of(String prompt) {
        return new GeminiRequest(prompt, null, null, null, null);
    }

    public GeminiRequest withSystemInstruction(String instruction) {
        return new GeminiRequest(prompt, instruction, temperature, responseSchema, maxOutputTokens);
    }

    public GeminiRequest withTemperature(double value) {
        return new GeminiRequest(prompt, systemInstruction, value, responseSchema, maxOutputTokens);
    }

    public GeminiRequest withResponseSchema(Map<String, Object> schema) {
        return new GeminiRequest(prompt, systemInstruction, temperature, schema, maxOutputTokens);
    }

    public GeminiRequest withMaxOutputTokens(int value) {
        return new GeminiRequest(prompt, systemInstruction, temperature, responseSchema, value);
    }

    /** No imprime el prompt: puede traer datos de contactos. */
    @Override
    public String toString() {
        return "GeminiRequest[promptLength=" + (prompt == null ? 0 : prompt.length())
                + ", jsonSchema=" + (responseSchema != null) + "]";
    }
}
