package co.com.gtc.prospeccion.handoff.service;

/**
 * Tarea 5.6: quien responde un correo de prospección pasa al vendedor. Sobre su prospecto del CRM (el pm_leaflet que
 * creó la búsqueda) se crea una tarea pendiente a nombre de quien aprobó el correo; así aparece en sus tareas, en el
 * historial del prospecto y entra al embudo y a Oportunidades. Reemplaza un servicio de notificaciones aparte.
 */
public interface ReplyHandoffService {

    /**
     * Qué hizo una pasada.
     *
     * @param handedOff respuestas que pasaron al vendedor (tarea creada)
     * @param failed    respuestas que no se pudieron pasar ahora; se reintentan en la siguiente pasada
     * @param note      por qué no se hizo nada (apagado o falta configuración), o null
     */
    record HandoffResult(int handedOff, int failed, String note) {

        public static final HandoffResult NONE = new HandoffResult(0, 0, null);
    }

    /**
     * Pasa al vendedor las respuestas que aún no pasaron, la más antigua primero. Se llama después de leer el buzón
     * (MailboxSyncJob y las herramientas locales). Cada respuesta pasa una sola vez: queda el evento "Pasó al vendedor".
     */
    HandoffResult handOffPending();
}
