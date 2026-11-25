package co.com.gtc.prospeccion.sending.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.Mailbox;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.draft.service.impl.DraftServiceImpl;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.integration.mailbox.MailboxSendException;
import co.com.gtc.prospeccion.integration.mailbox.MessageIds;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.dto.DispatchResultDTO;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

/**
 * Envío real de la cola (tarea 5.2). Cada correo va en su propia transacción: se toma con FOR UPDATE SKIP LOCKED (dos
 * instancias nunca envían el mismo), se revisa otra vez la baja y el borrador, se arma con la plantilla y el pie
 * legal, se entrega al buzón y queda Enviado (con su evento) o se reintenta.
 * <p>
 * Si el servicio se cae justo después de entregar el correo y antes de guardar, ese correo podría salir dos veces: es
 * el costo de no perder ninguno, y el bloqueo hace que en la operación normal no pase.
 */
@Component
public class SendDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SendDispatcher.class);
    private static final Pattern EMAIL = Pattern.compile("[^\\s<>()\"']+@[^\\s<>()\"']+");

    enum Outcome { NONE, SENT, RETRY, FAILED, CANCELLED }

    private final SendQueueRepository queue;
    private final DraftRepository drafts;
    private final EvidenceRepository evidence;
    private final TenantConfigRepository configs;
    private final MasterTableRepository masterTable;
    private final SendGuardService guard;
    private final EmailComposer composer;
    private final TrackingService tracking;
    private final DateTableResolver dateTable;
    private final ProspeccionProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public SendDispatcher(SendQueueRepository queue, DraftRepository drafts, EvidenceRepository evidence,
            TenantConfigRepository configs, MasterTableRepository masterTable, SendGuardService guard,
            EmailComposer composer, TrackingService tracking, DateTableResolver dateTable,
            ProspeccionProperties properties, TransactionTemplate transaction) {
        this(queue, drafts, evidence, configs, masterTable, guard, composer, tracking, dateTable, properties,
                transaction, Clock.systemUTC());
    }

    SendDispatcher(SendQueueRepository queue, DraftRepository drafts, EvidenceRepository evidence,
            TenantConfigRepository configs, MasterTableRepository masterTable, SendGuardService guard,
            EmailComposer composer, TrackingService tracking, DateTableResolver dateTable,
            ProspeccionProperties properties, TransactionTemplate transaction, Clock clock) {
        this.queue = queue;
        this.drafts = drafts;
        this.evidence = evidence;
        this.configs = configs;
        this.masterTable = masterTable;
        this.guard = guard;
        this.composer = composer;
        this.tracking = tracking;
        this.dateTable = dateTable;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Envía los correos con turno hasta que no haya más, se llegue al límite diario, al tope por pasada o (si no es
     * forzado) al espaciado entre envíos.
     *
     * @param ignoreSchedule solo herramientas locales: no espera el turno ni el espaciado
     */
    public DispatchResultDTO dispatch(MailboxPort port, boolean ignoreSchedule) {
        List<String> missing = composer.missingSettings();
        if (!missing.isEmpty()) {
            String reason = "Falta " + String.join(", ", missing);
            log.warn("No se envía nada. {}", reason);
            return DispatchResultDTO.stopped(reason);
        }
        if (port.requiresMailboxKey() && configs.findFirstByFlgActiveTrueOrderByIdDesc()
                .map(TenantConfigET::getMailboxSsmKey).filter(k -> !k.isBlank()).isEmpty()) {
            log.warn("No se envía nada: la configuración vigente no tiene buzón (MAILBOX_SSM_KEY, lo carga TI)");
            return DispatchResultDTO.stopped("La configuración vigente no tiene buzón (MAILBOX_SSM_KEY)");
        }

        Mailbox settings = properties.getMailbox();
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int cancelled = 0;
        String stoppedBy = "Tope de " + settings.getMaxPerRun() + " correos por pasada";
        for (int i = 0; i < Math.max(1, settings.getMaxPerRun()); i++) {
            ZonedDateTime now = ZonedDateTime.now(clock);
            if (!guard.hasDailyQuota(now)) {
                stoppedBy = "Límite diario alcanzado";
                break;
            }
            if (!ignoreSchedule && !spacingElapsed(now)) {
                stoppedBy = "Espaciado entre correos";
                break;
            }
            LocalDateTime dueBefore = ignoreSchedule ? utc(now).plusYears(10) : utc(now);
            Outcome outcome = transaction.execute(status -> dispatchOne(port, dueBefore));
            if (outcome == null || outcome == Outcome.NONE) {
                stoppedBy = "No hay más correos con turno";
                break;
            }
            switch (outcome) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
                default -> { }
            }
            if (outcome == Outcome.RETRY) {
                // El buzón no respondió: insistir con los demás ahora no sirve (y forzado volvería a tomar el mismo).
                stoppedBy = "El buzón no respondió: se reintenta más tarde";
                break;
            }
        }
        if (sent + retried + failed + cancelled > 0) {
            log.info("Envío: {} enviados, {} para reintentar, {} fallidos, {} cancelados. Paró por: {}", sent, retried,
                    failed, cancelled, stoppedBy);
        }
        return new DispatchResultDTO(sent, retried, failed, cancelled, stoppedBy);
    }

    /** Un correo, dentro de su transacción. */
    Outcome dispatchOne(MailboxPort port, LocalDateTime dueBefore) {
        Optional<Integer> claimed = queue.claimNextDue(dueBefore, SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED);
        if (claimed.isEmpty()) {
            return Outcome.NONE;
        }
        SendQueueET send = queue.findById(claimed.get()).orElse(null);
        if (send == null) {
            return Outcome.NONE;
        }
        DraftET draft = drafts.findById(send.getDraftId()).orElse(null);
        if (draft == null || draft.getStatus() == null || !DraftStatus.APPROVED.equals(draft.getStatus().getCode())) {
            return cancel(send, "el borrador ya no está aprobado");
        }
        if (send.getRecipientEmail() == null || guard.isSuppressed(send.getRecipientEmail())) {
            return cancel(send, "el destinatario está en la lista de bloqueo");
        }

        TenantConfigET config = configs.findFirstByFlgActiveTrueOrderByIdDesc().orElse(null);
        String organization = DraftServiceImpl.toContact(evidence.findByPmLeafletContactId(draft.getPmLeafletContactId()))
                .organizationName();
        String messageId = MessageIds.forSend(send.getId(), domainOf(properties.getMailbox().getFromAddress()));
        OutgoingEmail email;
        try {
            email = composer.compose(send, draft, config, organization, messageId);
        } catch (RuntimeException e) {
            // Un correo que no se puede armar (marca sin llenar, dato inválido) no se arregla reintentando.
            return fail(send, "no se pudo armar el correo: " + e.getMessage(), true);
        }

        try {
            String providerId = port.send(email);
            LocalDateTime sentAt = utc(ZonedDateTime.now(clock));
            send.setStatus(status(SendQueueStatus.SENT));
            send.setSentAt(sentAt);
            send.setAttempts(attempts(send) + 1);
            send.setProviderId(providerId == null || providerId.isBlank() ? messageId : providerId);
            touch(send);
            queue.save(send);
            tracking.registerEvent(send.getId(), EmailEventType.SENT, "sent:" + send.getId(), sentAt);
            log.info("Correo enviado: envío {}, borrador {}", send.getId(), send.getDraftId());
            return Outcome.SENT;
        } catch (MailboxSendException e) {
            return fail(send, e.getMessage(), e.isPermanent());
        } catch (RuntimeException e) {
            return fail(send, e.getClass().getSimpleName(), false);
        }
    }

    private Outcome fail(SendQueueET send, String reason, boolean permanent) {
        int attempts = attempts(send) + 1;
        int max = Math.max(1, properties.getMailbox().getMaxAttempts());
        send.setAttempts(attempts);
        String safeReason = reason == null ? "sin detalle" : EMAIL.matcher(reason).replaceAll("<correo>");
        touch(send);
        if (permanent || attempts >= max) {
            send.setStatus(status(SendQueueStatus.FAILED));
            queue.save(send);
            log.warn("Envío {} fallido ({} de {} intentos, {}): {}", send.getId(), attempts, max,
                    permanent ? "error permanente" : "sin más intentos", safeReason);
            return Outcome.FAILED;
        }
        long waitMinutes = (long) Math.max(1, properties.getMailbox().getRetryMinutes()) * attempts;
        send.setScheduledAt(utc(ZonedDateTime.now(clock)).plusMinutes(waitMinutes));
        queue.save(send);
        log.warn("Envío {} no salió (intento {} de {}): {}. Se reintenta en {} minutos", send.getId(), attempts, max,
                safeReason, waitMinutes);
        return Outcome.RETRY;
    }

    private Outcome cancel(SendQueueET send, String reason) {
        send.setStatus(status(SendQueueStatus.CANCELLED));
        touch(send);
        queue.save(send);
        log.info("Envío {} cancelado: {}", send.getId(), reason);
        return Outcome.CANCELLED;
    }

    private boolean spacingElapsed(ZonedDateTime now) {
        LocalDateTime last = queue.findLastSentAt();
        int spacing = guard.currentLimits().spacingSeconds();
        return last == null || !last.plusSeconds(spacing).isAfter(utc(now));
    }

    private void touch(SendQueueET send) {
        send.setLastUpdatedBy(send.getCreatedBy());
        send.setLastUpdatedDate(dateTable.today());
    }

    private MasterTableET status(String code) {
        return masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, code)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de envío 602/" + code
                        + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)"));
    }

    private static int attempts(SendQueueET send) {
        return send.getAttempts() == null ? 0 : send.getAttempts();
    }

    static String domainOf(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at < 0 ? "prospeccion.local" : address.substring(at + 1).strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static LocalDateTime utc(ZonedDateTime instant) {
        return instant.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
