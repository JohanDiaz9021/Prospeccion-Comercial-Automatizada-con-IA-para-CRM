package co.com.gtc.prospeccion.draft.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.draft.dto.request.DiscardDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.DraftPreviewRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.UpdateDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;

/**
 * Borradores de correo: generación con Gemini, consulta, edición y descarte.
 */
public interface DraftService {

    Page<DraftResponseDTO> list(String statusCode, Pageable pageable);

    DraftResponseDTO get(Integer id);

    /** Historial: todas las versiones del borrador de un contacto, la más nueva primero. */
    List<DraftResponseDTO> versions(Integer pmLeafletContactId);

    DraftResponseDTO update(Integer id, UpdateDraftRequestDTO request);

    DraftResponseDTO discard(Integer id, DiscardDraftRequestDTO request);

    /**
     * Redacta con Gemini una versión nueva del borrador de un contacto (tarea 4.3) y la deja pendiente de revisión.
     *
     * @param pmLeafletContactId contacto (pm_leafletcontact.ID) con su evidencia en la_evidence
     * @param userId             sc_user.ID que queda como CREATEDBY; null = el usuario de la petición
     */
    DraftResponseDTO generate(Integer pmLeafletContactId, Integer userId);

    /** Redacta un borrador para un contacto inventado, sin guardar nada (para ajustar el mensaje base). */
    DraftResponseDTO preview(DraftPreviewRequestDTO request);

    /** Llamado por MaintenanceJob. Devuelve cuántos borradores vencieron. */
    int expireOldDrafts();
}
