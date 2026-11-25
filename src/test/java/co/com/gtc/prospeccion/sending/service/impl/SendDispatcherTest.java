package co.com.gtc.prospeccion.sending.service.impl;

import static co.com.gtc.prospeccion.sending.service.impl.SendQueueServiceImplTest.estado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.integration.mailbox.MailboxSendException;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.dto.DispatchResultDTO;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

class SendDispatcherTest {

    /** Lunes 23 de noviembre de 2026, 10:00 en Bogotá. */
    static final Instant AHORA = Instant.parse("2026-11-23T15:00:00Z");
    static final LocalDateTime AHORA_UTC = LocalDateTime.of(2026, 11, 23, 15, 0);

    private final SendQueueRepository queue = mock(SendQueueRepository.class);
    private final DraftRepository drafts = mock(DraftRepository.class);
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final MasterTableRepository masterTable = mock(MasterTableRepository.class);
    private final SendGuardService guard = mock(SendGuardService.class);
    private final EmailComposer composer = mock(EmailComposer.class);
    private final TrackingService tracking = mock(TrackingService.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final MailboxPort port = mock(MailboxPort.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final MasterTableET enviado = estado(SendQueueStatus.TABLECODE, SendQueueStatus.SENT);
    private final MasterTableET fallido = estado(SendQueueStatus.TABLECODE, SendQueueStatus.FAILED);
    private final MasterTableET cancelado = estado(SendQueueStatus.TABLECODE, SendQueueStatus.CANCELLED);
    private SendDispatcher dispatcher;
    private SendQueueET envio;
    private DraftET borrador;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        properties.getMailbox().setFromAddress("prospeccion@gtc-prueba.local");
        when(transaction.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        when(composer.missingSettings()).thenReturn(List.of());
        when(composer.compose(any(), any(), any(), any(), any())).thenAnswer(i -> OutgoingEmail.builder()
                .to("ana.perez@clinica-demo.example").messageId(i.getArgument(4)).build());
        when(port.requiresMailboxKey()).thenReturn(false);
        when(guard.hasDailyQuota(any())).thenReturn(true);
        when(guard.currentLimits()).thenReturn(new SendSlotPlanner.Limits(20, 120));
        when(masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.SENT)).thenReturn(Optional.of(enviado));
        when(masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.FAILED)).thenReturn(Optional.of(fallido));
        when(masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.CANCELLED)).thenReturn(Optional.of(cancelado));
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(new TenantConfigET()));

        envio = new SendQueueET();
        ReflectionTestUtils.setField(envio, "id", 42);
        envio.setDraftId(7);
        envio.setRecipientEmail("ana.perez@clinica-demo.example");
        envio.setAttempts(0);
        envio.setScheduledAt(AHORA_UTC.minusMinutes(1));
        envio.setStatus(estado(SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED));
        borrador = new DraftET();
        borrador.setId(7);
        borrador.setPmLeafletContactId(900);
        borrador.setStatus(estado(DraftStatus.TABLECODE, DraftStatus.APPROVED));
        EvidenceET empresa = new EvidenceET();
        empresa.setField(EvidenceFields.ORGANIZATION_NAME);
        empresa.setValue("Clínica Demo");
        empresa.setSource(EvidenceFields.SOURCE_APOLLO);
        when(evidence.findByPmLeafletContactId(900)).thenReturn(List.of(empresa));
        // Un solo correo en cola: la primera vez se toma, la segunda ya no hay.
        when(queue.claimNextDue(any(), eq(SendQueueStatus.TABLECODE), eq(SendQueueStatus.QUEUED)))
                .thenReturn(Optional.of(42), Optional.empty());
        when(queue.findById(42)).thenReturn(Optional.of(envio));
        when(drafts.findById(7)).thenReturn(Optional.of(borrador));

        dispatcher = new SendDispatcher(queue, drafts, evidence, configs, masterTable, guard, composer, tracking,
                dateTable, properties, transaction, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void enviaYDejaElCorreoEnviadoConSuEvento() {
        when(port.send(any())).thenReturn("<pia.42.zz@gtc-prueba.local>");
        // Antes no había salido ninguno; después del envío, el último salió ahora.
        when(queue.findLastSentAt()).thenReturn(null, AHORA_UTC);

        DispatchResultDTO r = dispatcher.dispatch(port, false);

        assertThat(r.sent()).isEqualTo(1);
        assertThat(r.stoppedBy()).isEqualTo("Espaciado entre correos").as("tras enviar, espera el espaciado");
        assertThat(envio.getStatus()).isSameAs(enviado);
        assertThat(envio.getSentAt()).isEqualTo(AHORA_UTC);
        assertThat(envio.getAttempts()).isEqualTo(1);
        assertThat(envio.getProviderId()).isEqualTo("<pia.42.zz@gtc-prueba.local>");
        verify(tracking).registerEvent(42, EmailEventType.SENT, "sent:42", AHORA_UTC);
        ArgumentCaptor<String> messageId = ArgumentCaptor.forClass(String.class);
        verify(composer).compose(eq(envio), eq(borrador), any(), eq("Clínica Demo"), messageId.capture());
        assertThat(messageId.getValue()).matches("<pia\\.42\\.[a-z0-9]{12}@gtc-prueba\\.local>");
    }

    @Test
    void siElProveedorNoDaIdQuedaElMessageId() {
        when(port.send(any())).thenReturn(null);

        dispatcher.dispatch(port, false);

        assertThat(envio.getProviderId()).startsWith("<pia.42.");
    }

    @Test
    void sinTurnosNoHaceNada() {
        when(queue.claimNextDue(any(), anyInt(), anyString())).thenReturn(Optional.empty());

        DispatchResultDTO r = dispatcher.dispatch(port, false);

        assertThat(r.sent()).isZero();
        assertThat(r.stoppedBy()).isEqualTo("No hay más correos con turno");
        verify(port, never()).send(any());
    }

    @Test
    void siElContactoSeDioDeBajaSeCancelaSinEnviar() {
        when(guard.isSuppressed("ana.perez@clinica-demo.example")).thenReturn(true);

        DispatchResultDTO r = dispatcher.dispatch(port, false);

        assertThat(r.cancelled()).isEqualTo(1);
        assertThat(envio.getStatus()).isSameAs(cancelado);
        verify(port, never()).send(any());
        verify(tracking, never()).registerEvent(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void siElBorradorYaNoEstaAprobadoSeCancela() {
        borrador.setStatus(estado(DraftStatus.TABLECODE, DraftStatus.DISCARDED));

        assertThat(dispatcher.dispatch(port, false).cancelled()).isEqualTo(1);
        verify(port, never()).send(any());
    }

    @Test
    void unErrorTemporalSeReintentaMasTarde() {
        when(port.send(any())).thenThrow(new MailboxSendException("Servidor caído", false, null));

        DispatchResultDTO r = dispatcher.dispatch(port, false);

        assertThat(r.retried()).isEqualTo(1);
        assertThat(r.stoppedBy()).isEqualTo("El buzón no respondió: se reintenta más tarde");
        assertThat(envio.getAttempts()).isEqualTo(1);
        assertThat(envio.getStatus().getCode()).isEqualTo(SendQueueStatus.QUEUED);
        assertThat(envio.getScheduledAt()).isEqualTo(AHORA_UTC.plusMinutes(15));
        verify(tracking, never()).registerEvent(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void forzadoConElBuzonCaidoNoGastaTodosLosIntentosDeUnaVez() {
        when(queue.claimNextDue(any(), anyInt(), anyString())).thenReturn(Optional.of(42)); // seguiría "vencido"
        when(port.send(any())).thenThrow(new MailboxSendException("Servidor caído", false, null));

        DispatchResultDTO r = dispatcher.dispatch(port, true);

        assertThat(r.retried()).isEqualTo(1);
        assertThat(r.failed()).isZero();
        assertThat(envio.getAttempts()).isEqualTo(1);
        verify(port, times(1)).send(any());
    }

    @Test
    void unaDireccionRechazadaNoFrenaALosDemas() {
        SendQueueET otro = new SendQueueET();
        ReflectionTestUtils.setField(otro, "id", 43);
        otro.setDraftId(7);
        otro.setRecipientEmail("luis@logistica-demo.example");
        otro.setAttempts(0);
        otro.setStatus(estado(SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED));
        when(queue.claimNextDue(any(), anyInt(), anyString())).thenReturn(Optional.of(42), Optional.of(43), Optional.empty());
        when(queue.findById(43)).thenReturn(Optional.of(otro));
        when(port.send(any())).thenThrow(new MailboxSendException("Rechazada", true, null)).thenReturn("id");

        DispatchResultDTO r = dispatcher.dispatch(port, true);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.sent()).isEqualTo(1);
    }

    @Test
    void elUltimoIntentoQuedaFallido() {
        envio.setAttempts(2);
        when(port.send(any())).thenThrow(new MailboxSendException("Servidor caído", false, null));

        assertThat(dispatcher.dispatch(port, false).failed()).isEqualTo(1);
        assertThat(envio.getAttempts()).isEqualTo(3);
        assertThat(envio.getStatus()).isSameAs(fallido);
    }

    @Test
    void unErrorPermanenteNoSeReintenta() {
        when(port.send(any())).thenThrow(new MailboxSendException("Dirección rechazada <ana@x.example>", true, null));

        assertThat(dispatcher.dispatch(port, false).failed()).isEqualTo(1);
        assertThat(envio.getAttempts()).isEqualTo(1);
        assertThat(envio.getStatus()).isSameAs(fallido);
    }

    @Test
    void unCorreoQueNoSePuedeArmarFallaSinLlegarAlBuzon() {
        when(composer.compose(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("{{nombre}}"));

        assertThat(dispatcher.dispatch(port, false).failed()).isEqualTo(1);
        verify(port, never()).send(any());
    }

    @Test
    void sinPieLegalNoTocaLaCola() {
        when(composer.missingSettings()).thenReturn(List.of("el NIT del pie legal (LEGAL_NIT, tarea 1.10)"));

        DispatchResultDTO r = dispatcher.dispatch(port, false);

        assertThat(r.stoppedBy()).contains("LEGAL_NIT");
        verify(queue, never()).claimNextDue(any(), anyInt(), anyString());
    }

    @Test
    void conGmailOGraphSinBuzonEnLaConfiguracionNoEnvia() {
        when(port.requiresMailboxKey()).thenReturn(true);

        assertThat(dispatcher.dispatch(port, false).stoppedBy()).contains("MAILBOX_SSM_KEY");
        verify(queue, never()).claimNextDue(any(), anyInt(), anyString());
    }

    @Test
    void sinCupoDiarioParaAntesDeTomarNada() {
        when(guard.hasDailyQuota(any())).thenReturn(false);

        assertThat(dispatcher.dispatch(port, false).stoppedBy()).isEqualTo("Límite diario alcanzado");
        verify(queue, never()).claimNextDue(any(), anyInt(), anyString());
    }

    @Test
    void siElUltimoSalioHacePocoEsperaElEspaciado() {
        when(queue.findLastSentAt()).thenReturn(AHORA_UTC.minusSeconds(60));

        assertThat(dispatcher.dispatch(port, false).stoppedBy()).isEqualTo("Espaciado entre correos");
        verify(port, never()).send(any());
    }

    @Test
    void conElEspaciadoCumplidoEnvia() {
        when(queue.findLastSentAt()).thenReturn(AHORA_UTC.minusSeconds(120));
        when(port.send(any())).thenReturn("id");

        assertThat(dispatcher.dispatch(port, false).sent()).isEqualTo(1);
    }

    @Test
    void forzadoNoEsperaTurnoNiEspaciadoPeroRespetaElTope() {
        properties.getMailbox().setMaxPerRun(2);
        when(queue.findLastSentAt()).thenReturn(AHORA_UTC.minusSeconds(5));
        when(queue.claimNextDue(any(), anyInt(), anyString())).thenReturn(Optional.of(42));
        when(port.send(any())).thenReturn("id");

        DispatchResultDTO r = dispatcher.dispatch(port, true);

        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.stoppedBy()).isEqualTo("Tope de 2 correos por pasada");
        ArgumentCaptor<LocalDateTime> hasta = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(queue, times(2)).claimNextDue(hasta.capture(), anyInt(), anyString());
        assertThat(hasta.getValue()).isAfter(AHORA_UTC.plusYears(1));
    }

    @Test
    void elDominioDelMessageIdEsElDelBuzon() {
        assertThat(SendDispatcher.domainOf("Prospeccion@GTC.com.co")).isEqualTo("gtc.com.co");
        assertThat(SendDispatcher.domainOf(null)).isEqualTo("prospeccion.local");
    }
}
