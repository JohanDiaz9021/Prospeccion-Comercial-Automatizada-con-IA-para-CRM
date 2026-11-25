package co.com.gtc.prospeccion.handoff.service.impl;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.CrmTask;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.config.PropertiesConfig.Handoff;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.draft.service.impl.DraftServiceImpl;
import co.com.gtc.prospeccion.handoff.service.ReplyHandoffService;
import co.com.gtc.prospeccion.integration.gemini.dto.DraftContact;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.tracking.entities.EmailEventET;
import co.com.gtc.prospeccion.tracking.repository.EmailEventRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

/**
 * Tarea 5.6. Cada respuesta pendiente va en su propia transacción: si el CRM falla con una, las demás siguen y esa se
 * reintenta en la siguiente pasada (no tiene todavía el evento "Pasó al vendedor").
 */
@Service
public class ReplyHandoffServiceImpl implements ReplyHandoffService {

    private static final Logger log = LoggerFactory.getLogger(ReplyHandoffServiceImpl.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final int MAX_NAME = 100;
    static final int MAX_OBSERVATION = 500;

    /** IDs de sy_mastertable de la tarea, resueltos una vez por pasada. */
    record TaskCatalog(Integer typeId, Integer priorityId, Integer periodicityId, String dueHour) {
    }

    private final EmailEventRepository events;
    private final SendQueueRepository queue;
    private final DraftRepository drafts;
    private final EvidenceRepository evidence;
    private final MasterTableRepository masterTable;
    private final CrmProspectPort crm;
    private final TrackingService tracking;
    private final JdbcTemplate jdbc;
    private final Ley2300Calendar calendar;
    private final ProspeccionProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public ReplyHandoffServiceImpl(EmailEventRepository events, SendQueueRepository queue, DraftRepository drafts,
            EvidenceRepository evidence, MasterTableRepository masterTable, CrmProspectPort crm,
            TrackingService tracking, JdbcTemplate jdbc, Ley2300Calendar calendar, ProspeccionProperties properties,
            TransactionTemplate transaction) {
        this(events, queue, drafts, evidence, masterTable, crm, tracking, jdbc, calendar, properties, transaction,
                Clock.systemUTC());
    }

    ReplyHandoffServiceImpl(EmailEventRepository events, SendQueueRepository queue, DraftRepository drafts,
            EvidenceRepository evidence, MasterTableRepository masterTable, CrmProspectPort crm,
            TrackingService tracking, JdbcTemplate jdbc, Ley2300Calendar calendar, ProspeccionProperties properties,
            TransactionTemplate transaction, Clock clock) {
        this.events = events;
        this.queue = queue;
        this.drafts = drafts;
        this.evidence = evidence;
        this.masterTable = masterTable;
        this.crm = crm;
        this.tracking = tracking;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public HandoffResult handOffPending() {
        Handoff settings = properties.getHandoff();
        if (!settings.isEnabled()) {
            return new HandoffResult(0, 0, "Pasar al vendedor está apagado (HANDOFF_ENABLED=false)");
        }
        List<Integer> pending = events.findRepliedWithoutHandoff(EmailEventType.TABLECODE, EmailEventType.REPLY,
                EmailEventType.HANDOFF, PageRequest.of(0, Math.max(1, settings.getMaxPerRun())));
        if (pending.isEmpty()) {
            return HandoffResult.NONE;
        }
        TaskCatalog catalog;
        try {
            catalog = catalog(settings);
        } catch (IllegalStateException e) {
            log.warn("{} respuestas esperan pasar al vendedor: {}", pending.size(), e.getMessage());
            return new HandoffResult(0, pending.size(), e.getMessage());
        }

        int handedOff = 0;
        int failed = 0;
        for (Integer sendId : pending) {
            try {
                transaction.executeWithoutResult(status -> handOff(sendId, catalog));
                handedOff++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("La respuesta del envío {} no pasó al vendedor; se reintenta en la siguiente pasada: {}", sendId,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (handedOff + failed > 0) {
            log.info("Pasar al vendedor: {} con tarea creada, {} para reintentar", handedOff, failed);
        }
        return new HandoffResult(handedOff, failed, null);
    }

    /** Crea la tarea del vendedor sobre el prospecto y deja el evento "Pasó al vendedor". */
    void handOff(Integer sendId, TaskCatalog catalog) {
        SendQueueET send = queue.findById(sendId)
                .orElseThrow(() -> new IllegalStateException("No existe el envío " + sendId));
        DraftET draft = drafts.findById(send.getDraftId())
                .orElseThrow(() -> new IllegalStateException("No existe el borrador " + send.getDraftId()));
        UserET seller = draft.getApprovedBy();
        if (seller == null || seller.getId() == null) {
            throw new IllegalStateException("el borrador " + draft.getId() + " no tiene quién lo aprobó");
        }
        Integer contactId = draft.getPmLeafletContactId();
        List<Integer> leaflets = jdbc.queryForList("SELECT PM_LEAFLETID FROM pm_leafletcontact WHERE ID = ?",
                Integer.class, contactId);
        if (leaflets.isEmpty() || leaflets.get(0) == null) {
            throw new IllegalStateException("el contacto " + contactId + " no tiene prospecto en el CRM");
        }
        Integer leafletId = leaflets.get(0);
        DraftContact contact = DraftServiceImpl.toContact(evidence.findByPmLeafletContactId(contactId));
        LocalDateTime repliedAtUtc = events.findFirstBySendQueueIdAndType_TableCodeAndType_CodeOrderByOccurredAtAsc(sendId,
                EmailEventType.TABLECODE, EmailEventType.REPLY).map(EmailEventET::getOccurredAt)
                .orElseGet(() -> LocalDateTime.now(clock));

        CreateTaskRequestDTO request = task(contact, draft.getSubject(), repliedAtUtc, leafletId, catalog);
        Long taskId = crm.createTask(request, seller.getId());
        tracking.registerEvent(sendId, EmailEventType.HANDOFF, "handoff:" + sendId, LocalDateTime.now(clock));
        log.info("Respuesta del envío {} pasó al vendedor (usuario {}): tarea {} en el prospecto {}", sendId,
                seller.getId(), taskId, leafletId);
    }

    CreateTaskRequestDTO task(DraftContact contact, String subject, LocalDateTime repliedAtUtc, Integer leafletId,
            TaskCatalog catalog) {
        String nombre = join(" · ", contact.firstName(), contact.organizationName());
        String name = "Respondió a Prospección IA" + (nombre.isEmpty() ? "" : ": " + nombre);
        String repliedLocal = repliedAtUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(calendar.zone()).format(FECHA);
        StringBuilder observation = new StringBuilder("Respondió el ").append(repliedLocal)
                .append(" (hora de Colombia) al correo «").append(subject == null ? "" : subject.strip()).append("».");
        if (contact.title() != null) {
            observation.append(" Cargo: ").append(contact.title()).append('.');
        }
        if (contact.organizationName() != null) {
            observation.append(" Empresa: ").append(contact.organizationName()).append('.');
        }
        observation.append(" Lea la respuesta en el buzón de prospección y contáctelo. Tarea creada por Prospección IA.");

        return CreateTaskRequestDTO.builder()
                .mtTaskTypeId(catalog.typeId())
                .mtPriority(catalog.priorityId())
                .mtPeriodicity(catalog.periodicityId())
                .name(cut(name, MAX_NAME))
                .observation(cut(observation.toString(), MAX_OBSERVATION))
                .expirationDate(dueDate(ZonedDateTime.now(clock).withZoneSameInstant(calendar.zone()).toLocalDate())
                        .toString())
                .expirationHour(catalog.dueHour())
                .flgRemember(true)
                .leafletId(leafletId)
                .build();
    }

    /**
     * Vencimiento: el N-ésimo día hábil después de hoy (sin sábados, domingos ni festivos de Colombia). Con N = 0, hoy
     * si es hábil o el siguiente hábil. Una respuesta del sábado con N = 1 vence el lunes.
     */
    LocalDate dueDate(LocalDate today) {
        int remaining = Math.max(0, properties.getHandoff().getDueBusinessDays());
        LocalDate date = today;
        if (remaining == 0) {
            while (!isBusinessDay(date)) {
                date = date.plusDays(1);
            }
            return date;
        }
        while (remaining > 0) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }

    private boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !calendar.isHoliday(date);
    }

    /** Tipo (obligatorio), prioridad y periodicidad configurados, como IDs del catálogo del tenant. */
    TaskCatalog catalog(Handoff settings) {
        String typeCode = settings.getTaskTypeCode();
        if (typeCode == null || typeCode.isBlank()) {
            throw new IllegalStateException("falta el tipo de tarea (HANDOFF_TASK_TYPE_CODE, catálogo 246 del CRM)");
        }
        Integer typeId = masterTable.findFirstByTableCodeAndCode(CrmTask.TYPE_TABLECODE, typeCode.strip())
                .map(MasterTableET::getId)
                .orElseThrow(() -> new IllegalStateException("el tipo de tarea " + typeCode
                        + " no existe en el catálogo 246 del CRM (HANDOFF_TASK_TYPE_CODE)"));
        String hour;
        try {
            // Acepta 8:30 y 08:30; el CRM la recibe siempre como HH:mm.
            hour = LocalTime.parse(settings.getDueHour().strip(), DateTimeFormatter.ofPattern("H:mm"))
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException e) {
            throw new IllegalStateException("la hora de vencimiento no es HH:mm (HANDOFF_DUE_HOUR)");
        }
        return new TaskCatalog(typeId, optional(CrmTask.PRIORITY_TABLECODE, settings.getPriorityCode(), "HANDOFF_PRIORITY_CODE"),
                optional(CrmTask.PERIODICITY_TABLECODE, settings.getPeriodicityCode(), "HANDOFF_PERIODICITY_CODE"), hour);
    }

    private Integer optional(Integer tableCode, String code, String variable) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Integer id = masterTable.findFirstByTableCodeAndCode(tableCode, code.strip()).map(MasterTableET::getId).orElse(null);
        if (id == null) {
            log.warn("{} = {} no existe en el catálogo {} del CRM: la tarea se crea sin ese dato", variable, code, tableCode);
        }
        return id;
    }

    private static String join(String separator, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (out.length() > 0) {
                    out.append(separator);
                }
                out.append(part.strip());
            }
        }
        return out.toString();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
