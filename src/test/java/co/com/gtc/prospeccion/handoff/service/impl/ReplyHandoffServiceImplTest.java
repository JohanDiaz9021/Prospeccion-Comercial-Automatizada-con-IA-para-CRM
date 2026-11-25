package co.com.gtc.prospeccion.handoff.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.CrmTask;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.exception.ExternalServiceException;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.handoff.service.ReplyHandoffService.HandoffResult;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.tracking.entities.EmailEventET;
import co.com.gtc.prospeccion.tracking.repository.EmailEventRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

class ReplyHandoffServiceImplTest {

    /** Viernes 27 de noviembre de 2026, 10:00 en Bogotá. */
    static final Instant VIERNES = Instant.parse("2026-11-27T15:00:00Z");

    private final EmailEventRepository events = mock(EmailEventRepository.class);
    private final SendQueueRepository queue = mock(SendQueueRepository.class);
    private final DraftRepository drafts = mock(DraftRepository.class);
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final MasterTableRepository masterTable = mock(MasterTableRepository.class);
    private final CrmProspectPort crm = mock(CrmProspectPort.class);
    private final TrackingService tracking = mock(TrackingService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private ReplyHandoffServiceImpl service;
    private DraftET borrador;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        doAnswer(i -> {
            ((Consumer<TransactionStatus>) i.getArgument(0)).accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(events.findRepliedWithoutHandoff(eq(EmailEventType.TABLECODE), eq(EmailEventType.REPLY),
                eq(EmailEventType.HANDOFF), any())).thenReturn(List.of(42));
        when(masterTable.findFirstByTableCodeAndCode(CrmTask.TYPE_TABLECODE, "1")).thenReturn(Optional.of(maestro(2461)));

        SendQueueET envio = new SendQueueET();
        ReflectionTestUtils.setField(envio, "id", 42);
        envio.setDraftId(7);
        when(queue.findById(42)).thenReturn(Optional.of(envio));
        borrador = new DraftET();
        borrador.setId(7);
        borrador.setPmLeafletContactId(900);
        borrador.setSubject("Clínica Demo: menos procesos manuales");
        UserET vendedora = new UserET();
        ReflectionTestUtils.setField(vendedora, "id", 15);
        borrador.setApprovedBy(vendedora);
        when(drafts.findById(7)).thenReturn(Optional.of(borrador));
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(900))).thenReturn(List.of(300));
        when(evidence.findByPmLeafletContactId(900)).thenReturn(List.of(dato(EvidenceFields.FIRST_NAME, "Ana"),
                dato(EvidenceFields.TITLE, "Gerente de TI"), dato(EvidenceFields.ORGANIZATION_NAME, "Clínica Demo")));
        EmailEventET respuesta = new EmailEventET();
        respuesta.setOccurredAt(LocalDateTime.of(2026, 11, 27, 14, 30));
        when(events.findFirstBySendQueueIdAndType_TableCodeAndType_CodeOrderByOccurredAtAsc(42, EmailEventType.TABLECODE,
                EmailEventType.REPLY)).thenReturn(Optional.of(respuesta));
        when(crm.createTask(any(), anyInt())).thenReturn(5001L);

        service = new ReplyHandoffServiceImpl(events, queue, drafts, evidence, masterTable, crm, tracking, jdbc,
                new Ley2300Calendar(properties), properties, transaction, Clock.fixed(VIERNES, ZoneOffset.UTC));
    }

    static MasterTableET maestro(int id) {
        MasterTableET m = new MasterTableET();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    static EvidenceET dato(String campo, String valor) {
        EvidenceET e = new EvidenceET();
        e.setField(campo);
        e.setValue(valor);
        e.setSource(EvidenceFields.SOURCE_APOLLO);
        return e;
    }

    @Test
    void quienRespondePasaAlVendedorConUnaTareaSobreSuProspecto() {
        HandoffResult r = service.handOffPending();

        assertThat(r).isEqualTo(new HandoffResult(1, 0, null));
        ArgumentCaptor<CreateTaskRequestDTO> tarea = ArgumentCaptor.forClass(CreateTaskRequestDTO.class);
        verify(crm).createTask(tarea.capture(), eq(15)); // a nombre de quien aprobó el correo
        CreateTaskRequestDTO t = tarea.getValue();
        assertThat(t.getLeafletId()).isEqualTo(300);
        assertThat(t.getMtTaskTypeId()).isEqualTo(2461);
        assertThat(t.getMtPriority()).isNull();
        assertThat(t.getName()).isEqualTo("Respondió a Prospección IA: Ana · Clínica Demo");
        assertThat(t.getExpirationDate()).isEqualTo("2026-11-30"); // viernes + 1 día hábil = lunes
        assertThat(t.getExpirationHour()).isEqualTo("10:00");
        assertThat(t.getFlgRemember()).isTrue();
        assertThat(t.getObservation())
                .contains("Respondió el 27/11/2026 09:30 (hora de Colombia)")
                .contains("«Clínica Demo: menos procesos manuales»")
                .contains("Cargo: Gerente de TI.").contains("Empresa: Clínica Demo.");
        verify(tracking).registerEvent(42, EmailEventType.HANDOFF, "handoff:42", LocalDateTime.of(2026, 11, 27, 15, 0));
    }

    @Test
    void siElCrmFallaQuedaParaLaSiguientePasada() {
        when(crm.createTask(any(), anyInt())).thenThrow(new ExternalServiceException("msa-cotizaciones", "caído", null));

        HandoffResult r = service.handOffPending();

        assertThat(r).isEqualTo(new HandoffResult(0, 1, null));
        verify(tracking, never()).registerEvent(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void sinQuienAproboNoSeCreaLaTarea() {
        borrador.setApprovedBy(null);

        assertThat(service.handOffPending().failed()).isEqualTo(1);
        verify(crm, never()).createTask(any(), anyInt());
    }

    @Test
    void sinProspectoEnElCrmNoSeCreaLaTarea() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(900))).thenReturn(List.of());

        assertThat(service.handOffPending().failed()).isEqualTo(1);
        verify(crm, never()).createTask(any(), anyInt());
    }

    @Test
    void conUnTipoDeTareaQueNoExisteNoIntentaNadaYLoDice() {
        properties.getHandoff().setTaskTypeCode("99");

        HandoffResult r = service.handOffPending();

        assertThat(r.handedOff()).isZero();
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.note()).contains("HANDOFF_TASK_TYPE_CODE");
        verify(crm, never()).createTask(any(), anyInt());
    }

    @Test
    void prioridadYPeriodicidadConfiguradasVanEnLaTarea() {
        properties.getHandoff().setPriorityCode("3");
        properties.getHandoff().setPeriodicityCode("1");
        properties.getHandoff().setDueHour("8:30");
        when(masterTable.findFirstByTableCodeAndCode(CrmTask.PRIORITY_TABLECODE, "3")).thenReturn(Optional.of(maestro(2473)));
        when(masterTable.findFirstByTableCodeAndCode(CrmTask.PERIODICITY_TABLECODE, "1")).thenReturn(Optional.empty());

        service.handOffPending();

        ArgumentCaptor<CreateTaskRequestDTO> tarea = ArgumentCaptor.forClass(CreateTaskRequestDTO.class);
        verify(crm).createTask(tarea.capture(), eq(15));
        assertThat(tarea.getValue().getMtPriority()).isEqualTo(2473);
        assertThat(tarea.getValue().getMtPeriodicity()).isNull(); // no existe: se crea sin ese dato
        assertThat(tarea.getValue().getExpirationHour()).isEqualTo("08:30");
    }

    @Test
    void apagadoNoMiraNada() {
        properties.getHandoff().setEnabled(false);

        assertThat(service.handOffPending().note()).contains("HANDOFF_ENABLED");
        verify(events, never()).findRepliedWithoutHandoff(any(), any(), any(), any());
    }

    @Test
    void sinRespuestasPendientesNoHaceNada() {
        when(events.findRepliedWithoutHandoff(any(), any(), any(), any())).thenReturn(List.of());

        assertThat(service.handOffPending()).isEqualTo(HandoffResult.NONE);
    }

    @Test
    void elVencimientoSaltaFinesDeSemanaYFestivos() {
        assertThat(service.dueDate(LocalDate.of(2026, 11, 28))).isEqualTo(LocalDate.of(2026, 11, 30)); // sábado -> lunes
        assertThat(service.dueDate(LocalDate.of(2026, 12, 7))).isEqualTo(LocalDate.of(2026, 12, 9)); // 8/12 es festivo
        properties.getHandoff().setDueBusinessDays(0);
        assertThat(service.dueDate(LocalDate.of(2026, 11, 29))).isEqualTo(LocalDate.of(2026, 11, 30)); // domingo -> lunes
        assertThat(service.dueDate(LocalDate.of(2026, 11, 27))).isEqualTo(LocalDate.of(2026, 11, 27));
    }
}
