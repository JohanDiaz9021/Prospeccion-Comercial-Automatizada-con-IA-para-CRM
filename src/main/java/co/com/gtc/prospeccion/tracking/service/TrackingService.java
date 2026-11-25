package co.com.gtc.prospeccion.tracking.service;

import java.time.LocalDateTime;
import java.util.List;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;

/**
 * Seguimiento de los correos enviados (tarea 5.4): lee el buzón, reconoce respuestas, rebotes, quejas y respuestas
 * automáticas, y registra los eventos. Rebotes duros, quejas y pedidos de baja bloquean la dirección para siempre.
 */
public interface TrackingService {

    /**
     * Qué pasó en una lectura. Duplicados = mensajes ya procesados en una lectura anterior; sin envío = mensajes que no
     * son respuesta a ningún correo de prospección.
     */
    record SyncResult(int read, int replies, int unsubscribes, int hardBounces, int softBounces, int complaints,
            int autoReplies, int unmatched, int duplicates) {

        public static final SyncResult EMPTY = new SyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Llamado por MailboxSyncJob, dentro del tenant: lee el buzón de la configuración vigente y procesa lo nuevo. */
    SyncResult syncMailbox();

    /** Procesa mensajes ya leídos del buzón. Repetir un mensaje no hace nada nuevo. */
    SyncResult process(List<IncomingMessage> messages);

    /** Registra un evento del envío. Devuelve false si ya existía uno con ese providerEventId (no lo repite). */
    boolean registerEvent(Integer sendQueueId, String eventTypeCode, String providerEventId, LocalDateTime occurredAtUtc);

    /** Primera apertura por píxel del envío (dato aproximado). Devuelve false si ya estaba registrada. */
    boolean registerOpen(Integer sendQueueId);
}
