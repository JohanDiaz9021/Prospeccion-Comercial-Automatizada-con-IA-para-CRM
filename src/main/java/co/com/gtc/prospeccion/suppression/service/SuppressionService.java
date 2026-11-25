package co.com.gtc.prospeccion.suppression.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.suppression.dto.SuppressionResponseDTO;

/**
 * Lista de bloqueo (bajas, rebotes duros, quejas). Nunca se vuelve a escribir a un correo bloqueado y no se quita
 * de la lista: la baja es permanente.
 */
public interface SuppressionService {

    /**
     * Bloquea el correo (en minúsculas, con su dominio). Si ya estaba bloqueado no hace nada y devuelve false.
     *
     * @param reasonCode   código del motivo (MasterTableCodes.SuppressionReason)
     * @param sourceSendId la_sendqueue.ID que originó el bloqueo, o null
     * @param by           usuario que queda en la auditoría
     */
    boolean suppress(String email, String reasonCode, Integer sourceSendId, UserET by);

    boolean isSuppressed(String email);

    /** La lista, la más reciente primero. */
    Page<SuppressionResponseDTO> list(Pageable pageable);
}
