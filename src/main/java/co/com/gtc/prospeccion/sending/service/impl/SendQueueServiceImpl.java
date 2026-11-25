package co.com.gtc.prospeccion.sending.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.service.impl.DraftServiceImpl;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.sending.dto.DispatchResultDTO;
import co.com.gtc.prospeccion.sending.dto.SendingStatusDTO;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.ColombianHolidays;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.sending.service.SendQueueService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Cola de envío (tarea 5.3). Al aprobar un borrador (5.1) se le asigna su turno: la hora en que saldrá, siempre
 * dentro de la ventana de la Ley 2300 y respetando el espaciado y el límite diario del tenant. El despachador
 * (SendDispatcherJob) envía lo que ya tiene turno con {@link SendDispatcher} (tarea 5.2).
 * <p>
 * Si dos aprobaciones del mismo tenant ocurren en el mismo instante pueden recibir turnos más juntos que el
 * espaciado; el despachador vuelve a aplicar el espaciado al enviar.
 */
@Service
public class SendQueueServiceImpl implements SendQueueService {

    private static final Logger log = LoggerFactory.getLogger(SendQueueServiceImpl.class);

    private final SendQueueRepository repository;
    private final SendGuardService guard;
    private final Ley2300Calendar calendar;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;
    private final CurrentUserProvider currentUserProvider;
    private final TenantConfigRepository configRepository;
    private final ObjectProvider<MailboxPort> mailbox;
    private final ProspeccionProperties properties;
    private final EvidenceRepository evidenceRepository;
    private final SendDispatcher dispatcher;
    private final Clock clock;

    @Autowired
    public SendQueueServiceImpl(SendQueueRepository repository, SendGuardService guard, Ley2300Calendar calendar,
            MasterTableRepository masterTableRepository, DateTableResolver dateTableResolver,
            CurrentUserProvider currentUserProvider, TenantConfigRepository configRepository,
            ObjectProvider<MailboxPort> mailbox, ProspeccionProperties properties,
            EvidenceRepository evidenceRepository, SendDispatcher dispatcher) {
        this(repository, guard, calendar, masterTableRepository, dateTableResolver, currentUserProvider,
                configRepository, mailbox, properties, evidenceRepository, dispatcher, Clock.systemUTC());
    }

    SendQueueServiceImpl(SendQueueRepository repository, SendGuardService guard, Ley2300Calendar calendar,
            MasterTableRepository masterTableRepository, DateTableResolver dateTableResolver,
            CurrentUserProvider currentUserProvider, TenantConfigRepository configRepository,
            ObjectProvider<MailboxPort> mailbox, ProspeccionProperties properties,
            EvidenceRepository evidenceRepository, SendDispatcher dispatcher, Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.guard = guard;
        this.calendar = calendar;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
        this.currentUserProvider = currentUserProvider;
        this.configRepository = configRepository;
        this.mailbox = mailbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SendQueueET enqueue(DraftET approvedDraft) {
        if (approvedDraft == null || approvedDraft.getId() == null) {
            throw new IllegalArgumentException("El borrador a encolar no está guardado");
        }
        MasterTableET draftStatus = approvedDraft.getStatus();
        if (draftStatus == null || !DraftStatus.APPROVED.equals(draftStatus.getCode())) {
            throw new IllegalStateException("Solo se encolan borradores aprobados (borrador " + approvedDraft.getId() + ")");
        }
        Optional<SendQueueET> existing = repository.findByDraftId(approvedDraft.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        // El destinatario sale de la evidencia del contacto (4.1). Sin correo o con baja, no se encola: la aprobación
        // está en la misma transacción y tampoco queda.
        String recipient = DraftServiceImpl.emailOf(
                evidenceRepository.findByPmLeafletContactId(approvedDraft.getPmLeafletContactId()));
        if (recipient == null) {
            throw new BusinessException(MessageKeys.CONTACT_WITHOUT_EMAIL,
                    "El contacto " + approvedDraft.getPmLeafletContactId() + " no tiene correo en su evidencia");
        }
        if (guard.isSuppressed(recipient)) {
            throw new BusinessException(MessageKeys.CONTACT_SUPPRESSED,
                    "El correo del contacto " + approvedDraft.getPmLeafletContactId() + " está en la lista de bloqueo");
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDateTime slotUtc = guard.nextAllowedSlotUtc(now);
        SendQueueET item = new SendQueueET();
        item.setDraftId(approvedDraft.getId());
        item.setRecipientEmail(recipient);
        item.setScheduledAt(slotUtc);
        item.setAttempts(0);
        item.setStatus(status(SendQueueStatus.QUEUED));
        item.setCreatedBy(approvedDraft.getApprovedBy() != null
                ? approvedDraft.getApprovedBy()
                : currentUserProvider.getCurrentUser());
        item.setCreatedDate(dateTableResolver.today());
        SendQueueET saved = repository.save(item);
        log.info("Borrador {} en cola para {} (hora de Colombia)", approvedDraft.getId(), local(slotUtc));
        return saved;
    }

    @Override
    public int dispatchPending() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (!guard.isWithinLey2300Window(now)) {
            log.debug("Fuera del horario de la Ley 2300: no se envía nada");
            return 0;
        }
        long due = repository.countDue(utc(now), SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED);
        if (due == 0) {
            return 0;
        }
        if (!guard.hasDailyQuota(now)) {
            log.info("Límite diario alcanzado o envío pausado (DAILY_SEND_LIMIT): {} correos esperan", due);
            return 0;
        }
        MailboxPort port = mailbox.getIfAvailable();
        if (port == null) {
            log.info("{} correos tienen turno, pero no hay buzón configurado (prospeccion.mailbox.provider = {})", due,
                    properties.getMailbox().getProvider());
            return 0;
        }
        return dispatcher.dispatch(port, false).sent();
    }

    @Override
    public DispatchResultDTO dispatchNowIgnoringSchedule() {
        MailboxPort port = mailbox.getIfAvailable();
        if (port == null) {
            return DispatchResultDTO.stopped("No hay buzón configurado (MAILBOX_PROVIDER = "
                    + properties.getMailbox().getProvider() + ")");
        }
        return dispatcher.dispatch(port, true);
    }

    @Override
    public SendingStatusDTO status() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(calendar.zone());
        SendSlotPlanner.Limits limits = guard.currentLimits();
        TenantConfigET config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc().orElse(null);
        boolean fromConfig = config != null && config.getDailySendLimit() != null && config.getMinSpacingSeconds() != null;

        Map<String, Long> byStatus = new HashMap<>();
        for (Object[] row : repository.countByStatus(SendQueueStatus.TABLECODE)) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        LocalDateTime next = repository
                .findFirstByStatus_TableCodeAndStatus_CodeOrderByScheduledAtAsc(SendQueueStatus.TABLECODE,
                        SendQueueStatus.QUEUED)
                .map(q -> local(q.getScheduledAt()))
                .orElse(null);

        return new SendingStatusDTO(
                now.toLocalDateTime(),
                calendar.isAllowed(now),
                calendar.nextAllowed(now).toLocalDateTime(),
                ColombianHolidays.forYear(now.getYear()).get(now.toLocalDate()),
                limits.dailyLimit(),
                limits.spacingSeconds(),
                fromConfig,
                guard.scheduledOn(now.toLocalDate()),
                byStatus.getOrDefault(SendQueueStatus.QUEUED, 0L),
                byStatus.getOrDefault(SendQueueStatus.SENT, 0L),
                byStatus.getOrDefault(SendQueueStatus.FAILED, 0L),
                byStatus.getOrDefault(SendQueueStatus.CANCELLED, 0L),
                next,
                properties.getMailbox().getProvider());
    }

    private MasterTableET status(String code) {
        return masterTableRepository.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, code)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de envío 602/" + code
                        + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)"));
    }

    private LocalDateTime local(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(calendar.zone()).toLocalDateTime();
    }

    /** UTC sin zona, como se guarda en la_sendqueue. */
    private static LocalDateTime utc(ZonedDateTime instant) {
        return instant.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
