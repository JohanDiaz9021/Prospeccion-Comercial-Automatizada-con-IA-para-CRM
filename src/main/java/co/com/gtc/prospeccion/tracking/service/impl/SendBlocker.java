package co.com.gtc.prospeccion.tracking.service.impl;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;

/**
 * Bloquea una dirección para siempre (lista de bloqueo) y cancela sus correos que siguen en cola. Lo usan el rebote
 * duro, la queja y el pedido de baja por respuesta (5.4). La auditoría queda a nombre de quien aprobó el envío.
 */
@Component
public class SendBlocker {

    /** @param newlySuppressed false si ya estaba bloqueada */
    public record Result(boolean newlySuppressed, int cancelledSends) {
    }

    private final SuppressionService suppressionService;
    private final SendQueueRepository sendQueueRepository;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;

    public SendBlocker(SuppressionService suppressionService, SendQueueRepository sendQueueRepository,
            MasterTableRepository masterTableRepository, DateTableResolver dateTableResolver) {
        this.suppressionService = suppressionService;
        this.sendQueueRepository = sendQueueRepository;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
    }

    /**
     * @param send       envío que originó el bloqueo
     * @param email      dirección a bloquear
     * @param reasonCode MasterTableCodes.SuppressionReason
     */
    @Transactional
    public Result block(SendQueueET send, String email, String reasonCode) {
        String address = email.strip().toLowerCase(Locale.ROOT);
        boolean nuevo = suppressionService.suppress(address, reasonCode, send.getId(), send.getCreatedBy());
        List<SendQueueET> pendientes = sendQueueRepository.findByRecipientEmailAndStatus_TableCodeAndStatus_Code(
                address, SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED);
        if (!pendientes.isEmpty()) {
            MasterTableET cancelado = masterTableRepository
                    .findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.CANCELLED)
                    .orElseThrow(() -> new IllegalStateException("Falta el estado de envío 602/4 en sy_mastertable"));
            for (SendQueueET q : pendientes) {
                q.setStatus(cancelado);
                q.setLastUpdatedBy(send.getCreatedBy());
                q.setLastUpdatedDate(dateTableResolver.today());
            }
            sendQueueRepository.saveAll(pendientes);
        }
        return new Result(nuevo, pendientes.size());
    }
}
