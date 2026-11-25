package co.com.gtc.prospeccion.sending.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.sending.dto.DispatchResultDTO;
import co.com.gtc.prospeccion.sending.dto.SendingStatusDTO;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class SendQueueServiceImplTest {

    /** Lunes 23 de noviembre de 2026, 10:00 en Bogotá (15:00 UTC). */
    static final Instant AHORA = Instant.parse("2026-11-23T15:00:00Z");

    private final SendQueueRepository repository = mock(SendQueueRepository.class);
    private final SendGuardService guard = mock(SendGuardService.class);
    private final MasterTableRepository masterTable = mock(MasterTableRepository.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final TenantConfigRepository configRepository = mock(TenantConfigRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MailboxPort> mailbox = mock(ObjectProvider.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final SendDispatcher dispatcher = mock(SendDispatcher.class);
    private final MasterTableET enCola = estado(SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED);
    private final DateTableET hoy = new DateTableET();
    private SendQueueServiceImpl service;

    static MasterTableET estado(Integer tableCode, String code) {
        MasterTableET m = new MasterTableET();
        ReflectionTestUtils.setField(m, "tableCode", tableCode);
        ReflectionTestUtils.setField(m, "code", code);
        return m;
    }

    static EvidenceET correo(String email) {
        EvidenceET e = new EvidenceET();
        e.setPmLeafletContactId(900);
        e.setSource("HUNTER");
        e.setField(EvidenceFields.EMAIL);
        e.setValue(email);
        return e;
    }

    static DraftET aprobado(int id) {
        DraftET d = new DraftET();
        d.setId(id);
        d.setPmLeafletContactId(900);
        d.setStatus(estado(DraftStatus.TABLECODE, DraftStatus.APPROVED));
        return d;
    }

    @BeforeEach
    void preparar() {
        when(masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED))
                .thenReturn(Optional.of(enCola));
        when(dateTable.today()).thenReturn(hoy);
        when(repository.findByDraftId(anyInt())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(guard.nextAllowedSlotUtc(any())).thenReturn(LocalDateTime.of(2026, 11, 23, 15, 2));
        when(configRepository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        when(evidence.findByPmLeafletContactId(900)).thenReturn(List.of(correo("Ana@Empresa-Demo.example")));
        service = new SendQueueServiceImpl(repository, guard, new Ley2300Calendar(properties), masterTable, dateTable,
                currentUser, configRepository, mailbox, properties, evidence, dispatcher, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void encolaElBorradorAprobadoConSuTurno() {
        DraftET borrador = aprobado(77);
        UserET aprobador = new UserET();
        borrador.setApprovedBy(aprobador);

        service.enqueue(borrador);

        ArgumentCaptor<SendQueueET> guardado = ArgumentCaptor.forClass(SendQueueET.class);
        verify(repository).save(guardado.capture());
        SendQueueET q = guardado.getValue();
        assertThat(q.getDraftId()).isEqualTo(77);
        assertThat(q.getRecipientEmail()).isEqualTo("ana@empresa-demo.example");
        assertThat(q.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 15, 2));
        assertThat(q.getAttempts()).isZero();
        assertThat(q.getStatus()).isSameAs(enCola);
        assertThat(q.getCreatedBy()).isSameAs(aprobador); // quien aprobó, no el usuario de la petición
        assertThat(q.getCreatedDate()).isSameAs(hoy);
    }

    @Test
    void siYaEstabaEnColaNoCreaOtra() {
        SendQueueET existente = new SendQueueET();
        when(repository.findByDraftId(77)).thenReturn(Optional.of(existente));

        assertThat(service.enqueue(aprobado(77))).isSameAs(existente);
        verify(repository, never()).save(any());
    }

    @Test
    void soloSeEncolanBorradoresAprobados() {
        DraftET pendiente = aprobado(77);
        pendiente.setStatus(estado(DraftStatus.TABLECODE, DraftStatus.PENDING_REVIEW));

        assertThatThrownBy(() -> service.enqueue(pendiente)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void fueraDeLaVentanaNoMiraLaCola() {
        when(guard.isWithinLey2300Window(any())).thenReturn(false);

        assertThat(service.dispatchPending()).isZero();
        verify(repository, never()).countDue(any(), anyInt(), anyString());
    }

    @Test
    void sinBuzonNoEnviaNada() {
        when(guard.isWithinLey2300Window(any())).thenReturn(true);
        when(guard.hasDailyQuota(any())).thenReturn(true);
        when(repository.countDue(LocalDateTime.of(2026, 11, 23, 15, 0), SendQueueStatus.TABLECODE,
                SendQueueStatus.QUEUED)).thenReturn(3L);
        when(mailbox.getIfAvailable()).thenReturn(null);

        assertThat(service.dispatchPending()).isZero();
    }

    @Test
    void conTurnosCupoYBuzonEnviaConElDespachador() {
        MailboxPort puerto = mock(MailboxPort.class);
        when(guard.isWithinLey2300Window(any())).thenReturn(true);
        when(guard.hasDailyQuota(any())).thenReturn(true);
        when(repository.countDue(any(), anyInt(), anyString())).thenReturn(2L);
        when(mailbox.getIfAvailable()).thenReturn(puerto);
        when(dispatcher.dispatch(puerto, false)).thenReturn(new DispatchResultDTO(2, 0, 0, 0, "No hay más correos con turno"));

        assertThat(service.dispatchPending()).isEqualTo(2);
    }

    @Test
    void enviarYaSinTurnoEsForzadoYSinBuzonLoDice() {
        when(mailbox.getIfAvailable()).thenReturn(null);
        assertThat(service.dispatchNowIgnoringSchedule().stoppedBy()).contains("No hay buzón configurado");

        MailboxPort puerto = mock(MailboxPort.class);
        when(mailbox.getIfAvailable()).thenReturn(puerto);
        when(dispatcher.dispatch(puerto, true)).thenReturn(new DispatchResultDTO(1, 0, 0, 0, "No hay más correos con turno"));
        assertThat(service.dispatchNowIgnoringSchedule().sent()).isEqualTo(1);
    }

    @Test
    void sinCupoDiarioNoEnviaNada() {
        when(guard.isWithinLey2300Window(any())).thenReturn(true);
        when(repository.countDue(any(), anyInt(), anyString())).thenReturn(3L);
        when(guard.hasDailyQuota(any())).thenReturn(false);

        assertThat(service.dispatchPending()).isZero();
        verify(mailbox, never()).getIfAvailable();
    }

    @Test
    void estadoDeLaColaEnHoraDeColombia() {
        when(guard.currentLimits()).thenReturn(new SendSlotPlanner.Limits(20, 120));
        when(guard.scheduledOn(LocalDate.of(2026, 11, 23))).thenReturn(4);
        when(repository.countByStatus(SendQueueStatus.TABLECODE)).thenReturn(List.of(
                new Object[] { SendQueueStatus.QUEUED, 3L }, new Object[] { SendQueueStatus.SENT, 9L }));
        SendQueueET proximo = new SendQueueET();
        proximo.setScheduledAt(LocalDateTime.of(2026, 11, 23, 15, 30));
        when(repository.findFirstByStatus_TableCodeAndStatus_CodeOrderByScheduledAtAsc(SendQueueStatus.TABLECODE,
                SendQueueStatus.QUEUED)).thenReturn(Optional.of(proximo));

        SendingStatusDTO estado = service.status();

        assertThat(estado.now()).isEqualTo(LocalDateTime.of(2026, 11, 23, 10, 0));
        assertThat(estado.withinWindow()).isTrue();
        assertThat(estado.queued()).isEqualTo(3);
        assertThat(estado.sent()).isEqualTo(9);
        assertThat(estado.failed()).isZero();
        assertThat(estado.scheduledToday()).isEqualTo(4);
        assertThat(estado.nextScheduled()).isEqualTo(LocalDateTime.of(2026, 11, 23, 10, 30));
        assertThat(estado.limitsFromConfig()).isFalse();
        assertThat(estado.mailboxProvider()).isEqualTo("none");
    }

    @Test
    void sinCorreoEnLaEvidenciaNoSeEncola() {
        when(evidence.findByPmLeafletContactId(900)).thenReturn(List.of());

        assertThatThrownBy(() -> service.enqueue(aprobado(77)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblContactWithoutEmail"));
        verify(repository, never()).save(any());
    }

    @Test
    void unCorreoBloqueadoNoSeEncola() {
        when(guard.isSuppressed("ana@empresa-demo.example")).thenReturn(true);

        assertThatThrownBy(() -> service.enqueue(aprobado(77)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblContactSuppressed"));
        verify(repository, never()).save(any());
    }
}
