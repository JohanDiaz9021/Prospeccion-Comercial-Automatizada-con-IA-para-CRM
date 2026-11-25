package co.com.gtc.prospeccion.sending.service;

import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.sending.dto.SendingStatusDTO;
import co.com.gtc.prospeccion.sending.dto.DispatchResultDTO;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;

/**
 * Cola de envío de correos aprobados (tarea 5.3).
 */
public interface SendQueueService {

    /**
     * Encola un borrador aprobado con el siguiente turno libre (Ley 2300, espaciado y límite diario). Se llama dentro
     * de la transacción de aprobación (5.1): si falla, el borrador no queda aprobado. Si el borrador ya estaba en la
     * cola, devuelve esa fila sin crear otra.
     */
    SendQueueET enqueue(DraftET approvedDraft);

    /**
     * Llamado por SendDispatcherJob: envía lo que ya tiene turno, solo dentro de la ventana de la Ley 2300 y con cupo
     * diario. Devuelve cuántos correos envió.
     */
    int dispatchPending();

    /**
     * <b>Solo para las herramientas locales.</b> Envía ya los correos en cola sin esperar su turno ni la ventana de la
     * Ley 2300 ni el espaciado (sí respeta el límite diario y el tope por pasada). Con Mailpit nada sale de verdad.
     */
    DispatchResultDTO dispatchNowIgnoringSchedule();

    /** Estado de la cola y de la ventana de envío, para la pantalla. */
    SendingStatusDTO status();
}
