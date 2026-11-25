package co.com.gtc.prospeccion.draft.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.draft.service.ApprovalService;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.sending.service.SendQueueService;

/**
 * Aprobación humana (5.1). Ningún correo sale sin que una persona apruebe su última versión. Al aprobar, el
 * borrador entra a la cola de envío (5.3) con su turno en la MISMA transacción: si el encolado falla, no queda
 * aprobado. Si dos personas aprueban a la vez, la segunda choca con la llave única de la cola y recibe 409.
 */
@Service
public class ApprovalServiceImpl implements ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalServiceImpl.class);

    private final DraftDAO draftDAO;
    private final DraftSupport support;
    private final SendQueueService sendQueueService;
    private final CurrentUserProvider currentUserProvider;
    private final DateTableResolver dateTableResolver;

    public ApprovalServiceImpl(DraftDAO draftDAO, DraftSupport support, SendQueueService sendQueueService,
            CurrentUserProvider currentUserProvider, DateTableResolver dateTableResolver) {
        this.draftDAO = draftDAO;
        this.support = support;
        this.sendQueueService = sendQueueService;
        this.currentUserProvider = currentUserProvider;
        this.dateTableResolver = dateTableResolver;
    }

    @Override
    @Transactional
    public DraftResponseDTO approve(Integer draftId) {
        DraftET draft = support.find(draftId);
        support.requireLatestPending(draft);

        UserET approver = currentUserProvider.getCurrentUser();
        DateTableET today = dateTableResolver.today();
        draft.setStatus(support.status(DraftStatus.APPROVED));
        draft.setApprovedBy(approver);
        draft.setApprovedDate(today);
        draft.setLastUpdatedBy(approver);
        draft.setLastUpdatedDate(today);
        DraftET saved = draftDAO.save(draft);

        sendQueueService.enqueue(saved);
        log.info("Borrador {} (contacto {}, versión {}) aprobado y en cola", saved.getId(),
                saved.getPmLeafletContactId(), saved.getVersion());
        return support.describe(saved);
    }
}
