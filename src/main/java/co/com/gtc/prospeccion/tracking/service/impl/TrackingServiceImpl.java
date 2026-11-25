package co.com.gtc.prospeccion.tracking.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SuppressionReason;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.integration.mailbox.MessageIds;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tracking.entities.EmailEventET;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Classification;
import co.com.gtc.prospeccion.tracking.repository.EmailEventRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

/**
 * Tarea 5.4. Por cada mensaje que llega al buzón:
 * <ol>
 * <li>Lo reconoce ({@link InboundClassifier}): respuesta, pedido de baja, respuesta automática, rebote duro o
 * temporal, o queja.</li>
 * <li>Lo asocia a su envío: por nuestro Message-ID ({@link MessageIds}) en In-Reply-To, References o los encabezados
 * del correo original; si no, por el hilo; si no, por la dirección (el último envío a esa persona).</li>
 * <li>Registra el evento una sola vez (por el ID del mensaje en el proveedor) y aplica el efecto: rebote duro, queja
 * y pedido de baja bloquean la dirección para siempre y cancelan sus correos en cola; los rebotes temporales la
 * bloquean al llegar al límite; las respuestas automáticas no cuentan.</li>
 * </ol>
 * Un mensaje que falla no detiene a los demás. Nunca registra correos ni texto de personas en los logs.
 */
@Service
public class TrackingServiceImpl implements TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingServiceImpl.class);

    private final EmailEventRepository eventRepository;
    private final SendQueueRepository sendQueueRepository;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;
    private final ObjectProvider<MailboxPort> mailbox;
    private final TenantConfigRepository configRepository;
    private final SendBlocker blocker;
    private final ProspeccionProperties properties;
    private final Clock clock;

    @Autowired
    public TrackingServiceImpl(EmailEventRepository eventRepository, SendQueueRepository sendQueueRepository,
            MasterTableRepository masterTableRepository, DateTableResolver dateTableResolver,
            ObjectProvider<MailboxPort> mailbox, TenantConfigRepository configRepository, SendBlocker blocker,
            ProspeccionProperties properties) {
        this(eventRepository, sendQueueRepository, masterTableRepository, dateTableResolver, mailbox, configRepository,
                blocker, properties, Clock.systemUTC());
    }

    TrackingServiceImpl(EmailEventRepository eventRepository, SendQueueRepository sendQueueRepository,
            MasterTableRepository masterTableRepository, DateTableResolver dateTableResolver,
            ObjectProvider<MailboxPort> mailbox, TenantConfigRepository configRepository, SendBlocker blocker,
            ProspeccionProperties properties, Clock clock) {
        this.eventRepository = eventRepository;
        this.sendQueueRepository = sendQueueRepository;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
        this.mailbox = mailbox;
        this.configRepository = configRepository;
        this.blocker = blocker;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public SyncResult syncMailbox() {
        MailboxPort port = mailbox.getIfAvailable();
        if (port == null) {
            log.debug("Sin buzón configurado (prospeccion.mailbox.provider = {}): no hay nada que leer",
                    properties.getMailbox().getProvider());
            return SyncResult.EMPTY;
        }
        String mailboxKey = configRepository.findFirstByFlgActiveTrueOrderByIdDesc()
                .map(TenantConfigET::getMailboxSsmKey).filter(k -> !k.isBlank()).orElse(null);
        if (mailboxKey == null && port.requiresMailboxKey()) {
            log.warn("La configuración vigente no tiene buzón (MAILBOX_SSM_KEY): no se lee nada");
            return SyncResult.EMPTY;
        }
        LocalDateTime since = LocalDateTime.now(clock).minusHours(Math.max(1, properties.getMailbox().getLookbackHours()));
        SyncResult result = process(port.readInbox(mailboxKey, since));
        log.info("Buzón: {} mensajes, {} respuestas, {} bajas, {} rebotes duros, {} temporales, {} quejas, "
                + "{} automáticas, {} sin envío, {} ya procesados", result.read(), result.replies(), result.unsubscribes(),
                result.hardBounces(), result.softBounces(), result.complaints(), result.autoReplies(), result.unmatched(),
                result.duplicates());
        return result;
    }

    @Override
    public SyncResult process(List<IncomingMessage> messages) {
        Counter c = new Counter();
        for (IncomingMessage m : messages == null ? List.<IncomingMessage>of() : messages) {
            c.read++;
            try {
                handle(m, c);
            } catch (RuntimeException e) {
                log.error("No se pudo procesar un mensaje del buzón ({}): sigue con los demás", e.getClass().getSimpleName(), e);
            }
        }
        return c.result();
    }

    private void handle(IncomingMessage m, Counter c) {
        Classification classification = InboundClassifier.classify(m);
        Optional<SendQueueET> found = resolveSend(m, classification);
        if (found.isEmpty()) {
            c.unmatched++;
            return;
        }
        SendQueueET send = found.get();
        if (classification.kind() == InboundClassifier.Kind.AUTO_REPLY) {
            c.autoReplies++;
            return;
        }
        String eventId = eventId(m);
        LocalDateTime when = m.getReceivedAt() != null ? m.getReceivedAt() : LocalDateTime.now(clock);
        String email = recipientOf(send, classification, m);

        switch (classification.kind()) {
            case REPLY -> {
                if (registerEvent(send.getId(), EmailEventType.REPLY, eventId, when)) {
                    c.replies++;
                    // 5.6: ReplyHandoffService la pasa al vendedor después de la lectura (y la reintenta si el CRM falla).
                } else {
                    c.duplicates++;
                }
            }
            case UNSUBSCRIBE_REQUEST -> {
                // Mismo ID de evento que el enlace de baja (5.5): pedirla por los dos lados cuenta una sola vez.
                registerEvent(send.getId(), EmailEventType.UNSUBSCRIBE, "unsubscribe:" + send.getId(), when);
                if (email != null && blocker.block(send, email, SuppressionReason.UNSUBSCRIBE).newlySuppressed()) {
                    c.unsubscribes++;
                } else {
                    c.duplicates++;
                }
            }
            case HARD_BOUNCE -> {
                if (registerEvent(send.getId(), EmailEventType.BOUNCE, eventId, when)) {
                    c.hardBounces++;
                    if (email != null) {
                        blocker.block(send, email, SuppressionReason.HARD_BOUNCE);
                    }
                } else {
                    c.duplicates++;
                }
            }
            case SOFT_BOUNCE -> {
                if (registerEvent(send.getId(), EmailEventType.BOUNCE, eventId, when)) {
                    c.softBounces++;
                    int limit = Math.max(1, properties.getTracking().getSoftBounceLimit());
                    if (email != null && eventRepository.countByRecipientAndType(email, EmailEventType.TABLECODE,
                            EmailEventType.BOUNCE) >= limit) {
                        blocker.block(send, email, SuppressionReason.HARD_BOUNCE);
                    }
                } else {
                    c.duplicates++;
                }
            }
            case COMPLAINT -> {
                if (registerEvent(send.getId(), EmailEventType.UNSUBSCRIBE, eventId, when)) {
                    c.complaints++;
                    if (email != null) {
                        blocker.block(send, email, SuppressionReason.COMPLAINT);
                    }
                } else {
                    c.duplicates++;
                }
            }
            default -> c.unmatched++;
        }
    }

    /** Nuestro Message-ID, luego el hilo, luego la dirección (último correo enviado a esa persona). */
    Optional<SendQueueET> resolveSend(IncomingMessage m, Classification classification) {
        String references = m.getReferences() == null ? null : String.join(" ", m.getReferences());
        Optional<Integer> id = MessageIds.findSendId(m.getInReplyTo(), references, m.getReturnedHeaders(),
                m.getDeliveryStatus(), m.getFeedbackReport(),
                classification.kind() == InboundClassifier.Kind.REPLY ? null : m.getBodyText());
        if (id.isPresent()) {
            Optional<SendQueueET> send = sendQueueRepository.findById(id.get());
            if (send.isPresent()) {
                return send;
            }
        }
        if (m.getThreadId() != null && !m.getThreadId().isBlank()) {
            Optional<SendQueueET> send = sendQueueRepository.findFirstByThreadIdOrderByIdDesc(m.getThreadId());
            if (send.isPresent()) {
                return send;
            }
        }
        String address = classification.bouncedRecipient() != null ? classification.bouncedRecipient()
                : isBounceOrComplaint(classification) ? null : InboundClassifier.address(m.getFrom());
        if (address == null) {
            return Optional.empty();
        }
        return sendQueueRepository.findFirstByRecipientEmailAndStatus_TableCodeAndStatus_CodeOrderByIdDesc(address,
                SendQueueStatus.TABLECODE, SendQueueStatus.SENT);
    }

    private static boolean isBounceOrComplaint(Classification c) {
        return c.kind() == InboundClassifier.Kind.HARD_BOUNCE || c.kind() == InboundClassifier.Kind.SOFT_BOUNCE
                || c.kind() == InboundClassifier.Kind.COMPLAINT;
    }

    /** Destinatario del envío; en filas viejas sin él, el que rebotó o el remitente de la respuesta. */
    private static String recipientOf(SendQueueET send, Classification c, IncomingMessage m) {
        if (send.getRecipientEmail() != null && !send.getRecipientEmail().isBlank()) {
            return send.getRecipientEmail();
        }
        return c.bouncedRecipient() != null ? c.bouncedRecipient()
                : isBounceOrComplaint(c) ? null : InboundClassifier.address(m.getFrom());
    }

    /** ID del mensaje en el proveedor o, si no viene, su Message-ID: con él no se procesa dos veces. */
    private static String eventId(IncomingMessage m) {
        if (m.getProviderMessageId() != null && !m.getProviderMessageId().isBlank()) {
            return "msg:" + m.getProviderMessageId();
        }
        return m.getInternetMessageId() == null ? null : "mid:" + m.getInternetMessageId();
    }

    @Override
    public boolean registerOpen(Integer sendQueueId) {
        return registerEvent(sendQueueId, EmailEventType.OPEN, "open:" + sendQueueId, LocalDateTime.now(clock));
    }

    /**
     * Idempotente por providerEventId: si ya existe un evento con ese ID no se registra otro. La auditoría queda a
     * nombre de quien aprobó el envío (los eventos llegan del buzón o de la página pública, sin usuario).
     */
    @Override
    @Transactional
    public boolean registerEvent(Integer sendQueueId, String eventTypeCode, String providerEventId,
            LocalDateTime occurredAtUtc) {
        if (providerEventId != null && eventRepository.existsByProviderId(providerEventId)) {
            return false;
        }
        SendQueueET send = sendQueueRepository.findById(sendQueueId)
                .orElseThrow(() -> new IllegalArgumentException("No existe el envío " + sendQueueId));
        EmailEventET event = new EmailEventET();
        event.setSendQueueId(sendQueueId);
        event.setType(masterTableRepository.findFirstByTableCodeAndCode(EmailEventType.TABLECODE, eventTypeCode)
                .orElseThrow(() -> new IllegalStateException("Falta el tipo de evento 603/" + eventTypeCode
                        + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)")));
        event.setOccurredAt(occurredAtUtc);
        event.setProviderId(providerEventId);
        event.setCreatedBy(send.getCreatedBy());
        event.setCreatedDate(dateTableResolver.today());
        eventRepository.save(event);
        return true;
    }

    private static final class Counter {
        int read;
        int replies;
        int unsubscribes;
        int hardBounces;
        int softBounces;
        int complaints;
        int autoReplies;
        int unmatched;
        int duplicates;

        SyncResult result() {
            return new SyncResult(read, replies, unsubscribes, hardBounces, softBounces, complaints, autoReplies,
                    unmatched, duplicates);
        }
    }
}
