package co.com.gtc.prospeccion.integration.gemini;

/**
 * Respuesta de Gemini. Los tokens sirven para registrar el costo (tarea 4.2, la_creditledger).
 *
 * @param text         texto de la respuesta (JSON si se pidió con esquema); sin el razonamiento interno del modelo
 * @param model        versión del modelo que respondió
 * @param finishReason motivo de fin que informa Gemini (STOP, MAX_TOKENS...)
 * @param inputTokens  tokens de entrada
 * @param outputTokens tokens de salida, incluidos los de razonamiento ("thoughts"), que también se cobran
 * @param durationMs   duración total, con los reintentos
 * @param attempts     intentos hechos (1 = sin reintentos)
 */
public record GeminiResponse(
        String text,
        String model,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        long durationMs,
        int attempts) {
}
