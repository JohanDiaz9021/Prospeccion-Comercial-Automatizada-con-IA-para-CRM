package co.com.gtc.prospeccion.tracking.service;

import java.util.Optional;

/**
 * Baja de un destinatario (5.5). Quien llama debe haber fijado el tenant del token en TenantContext.
 */
public interface UnsubscribeService {

    /**
     * Lo que muestra la página antes de confirmar.
     *
     * @param maskedEmail          correo con la parte local oculta (a***@empresa.co)
     * @param alreadyUnsubscribed  si ya está en la lista de bloqueo
     */
    record Recipient(String maskedEmail, boolean alreadyUnsubscribed) {
    }

    /**
     * Resultado de la baja.
     *
     * @param newlySuppressed false si ya estaba bloqueado (la baja es idempotente)
     * @param cancelledSends  envíos en cola al mismo correo que se cancelaron
     */
    record Result(String maskedEmail, boolean newlySuppressed, int cancelledSends) {
    }

    /** Destinatario del envío, o vacío si el envío no existe o no tiene correo. */
    Optional<Recipient> recipientOf(Integer sendQueueId);

    /**
     * Bloquea el correo para siempre, registra el evento de baja (una sola vez) y cancela los otros envíos en cola a
     * ese correo. Todo en una transacción.
     */
    Optional<Result> unsubscribe(Integer sendQueueId);
}
