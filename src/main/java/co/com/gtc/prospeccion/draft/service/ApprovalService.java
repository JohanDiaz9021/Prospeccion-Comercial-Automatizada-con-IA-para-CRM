package co.com.gtc.prospeccion.draft.service;

import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;

/**
 * Aprobación humana de un borrador.
 */
public interface ApprovalService {

    /** Aprueba el borrador y lo encola para envío en la MISMA transacción. */
    DraftResponseDTO approve(Integer draftId);
}
