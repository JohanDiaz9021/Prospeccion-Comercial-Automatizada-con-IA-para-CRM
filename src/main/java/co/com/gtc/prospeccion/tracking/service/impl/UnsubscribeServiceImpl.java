package co.com.gtc.prospeccion.tracking.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SuppressionReason;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.draft.service.impl.DraftServiceImpl;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tracking.service.TrackingService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService;

/**
 * Baja automática (5.5): nadie interviene. El correo queda bloqueado para siempre (motivo "baja"), se registra un
 * solo evento de baja por envío y se cancelan los demás correos en cola a la misma dirección. La auditoría queda a
 * nombre de quien aprobó el envío. Nunca registra el correo en los logs.
 */
@Service
public class UnsubscribeServiceImpl implements UnsubscribeService {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeServiceImpl.class);

    private final SendQueueRepository sendQueueRepository;
    private final DraftRepository draftRepository;
    private final EvidenceRepository evidenceRepository;
    private final SuppressionService suppressionService;
    private final TrackingService trackingService;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;
    private final Clock clock;

    @Autowired
    public UnsubscribeServiceImpl(SendQueueRepository sendQueueRepository, DraftRepository draftRepository,
            EvidenceRepository evidenceRepository, SuppressionService suppressionService,
            TrackingService trackingService, MasterTableRepository masterTableRepository,
            DateTableResolver dateTableResolver) {
        this(sendQueueRepository, draftRepository, evidenceRepository, suppressionService, trackingService,
                masterTableRepository, dateTableResolver, Clock.systemUTC());
    }

    UnsubscribeServiceImpl(SendQueueRepository sendQueueRepository, DraftRepository draftRepository,
            EvidenceRepository evidenceRepository, SuppressionService suppressionService,
            TrackingService trackingService, MasterTableRepository masterTableRepository,
            DateTableResolver dateTableResolver, Clock clock) {
        this.sendQueueRepository = sendQueueRepository;
        this.draftRepository = draftRepository;
        this.evidenceRepository = evidenceRepository;
        this.suppressionService = suppressionService;
        this.trackingService = trackingService;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Recipient> recipientOf(Integer sendQueueId) {
        return sendQueueRepository.findById(sendQueueId)
                .flatMap(this::emailOf)
                .map(email -> new Recipient(mask(email), suppressionService.isSuppressed(email)));
    }

    @Override
    @Transactional
    public Optional<Result> unsubscribe(Integer sendQueueId) {
        Optional<SendQueueET> found = sendQueueRepository.findById(sendQueueId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SendQueueET send = found.get();
        Optional<String> email = emailOf(send);
        if (email.isEmpty()) {
            return Optional.empty();
        }

        boolean nuevo = suppressionService.suppress(email.get(), SuppressionReason.UNSUBSCRIBE, sendQueueId,
                send.getCreatedBy());
        trackingService.registerEvent(sendQueueId, EmailEventType.UNSUBSCRIBE, "unsubscribe:" + sendQueueId,
                LocalDateTime.now(clock));

        List<SendQueueET> pendientes = sendQueueRepository.findByRecipientEmailAndStatus_TableCodeAndStatus_Code(
                email.get(), SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED);
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
        log.info("Baja por enlace del envío {}: {} ({} envíos en cola cancelados)", sendQueueId,
                nuevo ? "correo bloqueado" : "ya estaba bloqueado", pendientes.size());
        return Optional.of(new Result(mask(email.get()), nuevo, pendientes.size()));
    }

    /** Destinatario guardado en la cola o, en filas viejas sin él, el correo de la evidencia del contacto. */
    private Optional<String> emailOf(SendQueueET send) {
        if (send.getRecipientEmail() != null && !send.getRecipientEmail().isBlank()) {
            return Optional.of(send.getRecipientEmail());
        }
        return draftRepository.findById(send.getDraftId())
                .map(d -> DraftServiceImpl.emailOf(evidenceRepository.findByPmLeafletContactId(d.getPmLeafletContactId())));
    }

    /** "laura@empresa.co" → "l****@empresa.co": la página pública no muestra el correo completo. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        return email.charAt(0) + "****" + email.substring(at);
    }
}
