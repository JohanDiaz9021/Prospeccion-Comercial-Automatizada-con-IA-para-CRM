package co.com.gtc.prospeccion.sending.dto;

/**
 * Qué hizo una pasada del despachador (tarea 5.2).
 *
 * @param sent      enviados
 * @param retried   fallaron con un error temporal y vuelven a intentarse más tarde
 * @param failed    fallaron del todo (error permanente o sin más intentos)
 * @param cancelled no se enviaron porque el destinatario se dio de baja o el borrador dejó de estar aprobado
 * @param stoppedBy por qué paró: sin turnos, límite diario, espaciado, tope por pasada o datos que faltan
 */
public record DispatchResultDTO(int sent, int retried, int failed, int cancelled, String stoppedBy) {

    public static DispatchResultDTO stopped(String reason) {
        return new DispatchResultDTO(0, 0, 0, 0, reason);
    }
}
