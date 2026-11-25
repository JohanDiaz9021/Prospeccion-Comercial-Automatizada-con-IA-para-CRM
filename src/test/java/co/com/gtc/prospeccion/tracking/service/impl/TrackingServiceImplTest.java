package co.com.gtc.prospeccion.tracking.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SuppressionReason;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.integration.mailbox.MailboxPort;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tracking.entities.EmailEventET;
import co.com.gtc.prospeccion.tracking.repository.EmailEventRepository;
import co.com.gtc.prospeccion.tracking.service.TrackingService.SyncResult;

/** Tarea 5.4: qué hace el sistema con cada mensaje que llega al buzón. */
class TrackingServiceImplTest {

    static final String NUESTRO_ID = "<pia.41.abcdefabcdef@prospeccion.example>";
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-24T15:00:00Z"), ZoneOffset.UTC);

    private final EmailEventRepository eventos = mock(EmailEventRepository.class);
    private final SendQueueRepository cola = mock(SendQueueRepository.class);
    private final MasterTableRepository maestras = mock(MasterTableRepository.class);
    private final DateTableResolver fechas = mock(DateTableResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MailboxPort> buzon = mock(ObjectProvider.class);
    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final SendBlocker bloqueo = mock(SendBlocker.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final List<EmailEventET> guardados = new ArrayList<>();
    private TrackingServiceImpl service;
    private SendQueueET envio;

    @BeforeEach
    void preparar() {
        envio = new SendQueueET();
        envio.setId(41);
        envio.setRecipientEmail("ana@clinica.example");
        envio.setThreadId("hilo-41");
        envio.setCreatedBy(new UserET());
        when(cola.findById(41)).thenReturn(Optional.of(envio));
        when(eventos.save(any())).thenAnswer(i -> {
            guardados.add(i.getArgument(0));
            return i.getArgument(0);
        });
        for (String code : List.of("1", "2", "3", "4", "5")) {
            MasterTableET tipo = new MasterTableET();
            ReflectionTestUtils.setField(tipo, "code", code);
            when(maestras.findFirstByTableCodeAndCode(EmailEventType.TABLECODE, code)).thenReturn(Optional.of(tipo));
        }
        when(bloqueo.block(any(), anyString(), anyString())).thenReturn(new SendBlocker.Result(true, 1));
        service = new TrackingServiceImpl(eventos, cola, maestras, fechas, buzon, configs, bloqueo, properties, RELOJ);
    }

    private static IncomingMessage.IncomingMessageBuilder mensaje(String id) {
        return IncomingMessage.builder().providerMessageId(id).receivedAt(LocalDateTime.of(2026, 11, 24, 14, 0));
    }

    private List<String> tiposGuardados() {
        return guardados.stream().map(e -> e.getType().getCode()).toList();
    }

    @Test
    void unaRespuestaSeAsociaPorNuestroMessageIdYSeRegistraUnaSolaVez() {
        IncomingMessage r = mensaje("m1").from("Ana <ana@clinica.example>").inReplyTo(NUESTRO_ID).bodyText("Me interesa").build();
        when(eventos.existsByProviderId("msg:m1")).thenReturn(false, true);

        SyncResult primera = service.process(List.of(r));
        SyncResult segunda = service.process(List.of(r));

        assertThat(primera.replies()).isEqualTo(1);
        assertThat(segunda.duplicates()).isEqualTo(1);
        assertThat(tiposGuardados()).containsExactly(EmailEventType.REPLY);
        assertThat(guardados.get(0).getSendQueueId()).isEqualTo(41);
        assertThat(guardados.get(0).getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 14, 0));
        verify(bloqueo, never()).block(any(), anyString(), anyString());
    }

    @Test
    void sinMessageIdSeAsociaPorElHiloOPorElRemitente() {
        when(cola.findFirstByThreadIdOrderByIdDesc("hilo-41")).thenReturn(Optional.of(envio));
        when(cola.findFirstByRecipientEmailAndStatus_TableCodeAndStatus_CodeOrderByIdDesc("ana@clinica.example",
                SendQueueStatus.TABLECODE, SendQueueStatus.SENT)).thenReturn(Optional.of(envio));

        SyncResult r = service.process(List.of(
                mensaje("m1").threadId("hilo-41").from("otra@x.example").bodyText("ok").build(),
                mensaje("m2").from("Ana <ANA@clinica.example>").bodyText("ok").build(),
                mensaje("m3").from("desconocido@x.example").bodyText("hola").build()));

        assertThat(r.replies()).isEqualTo(2);
        assertThat(r.unmatched()).isEqualTo(1);
    }

    @Test
    void reboteDuroBloqueaLaDireccionParaSiempre() {
        IncomingMessage rebote = mensaje("b1").from("mailer-daemon@googlemail.com")
                .deliveryStatus("Final-Recipient: rfc822; ana@clinica.example\nAction: failed\nStatus: 5.1.1")
                .returnedHeaders("Message-ID: " + NUESTRO_ID).build();

        SyncResult r = service.process(List.of(rebote));

        assertThat(r.hardBounces()).isEqualTo(1);
        assertThat(tiposGuardados()).containsExactly(EmailEventType.BOUNCE);
        verify(bloqueo).block(envio, "ana@clinica.example", SuppressionReason.HARD_BOUNCE);
    }

    @Test
    void reboteTemporalSoloBloqueaAlLlegarAlLimite() {
        properties.getTracking().setSoftBounceLimit(3);
        when(eventos.countByRecipientAndType("ana@clinica.example", EmailEventType.TABLECODE, EmailEventType.BOUNCE))
                .thenReturn(2L, 3L);
        IncomingMessage.IncomingMessageBuilder temporal = mensaje(null).returnedHeaders("Message-ID: " + NUESTRO_ID)
                .deliveryStatus("Final-Recipient: rfc822; ana@clinica.example\nAction: failed\nStatus: 4.2.2");

        service.process(List.of(temporal.providerMessageId("s1").build()));
        verify(bloqueo, never()).block(any(), anyString(), anyString());
        SyncResult r = service.process(List.of(temporal.providerMessageId("s2").build()));

        assertThat(r.softBounces()).isEqualTo(1);
        verify(bloqueo).block(envio, "ana@clinica.example", SuppressionReason.HARD_BOUNCE);
    }

    @Test
    void quejaYPedidoDeBajaBloqueanConSuMotivo() {
        IncomingMessage queja = mensaje("q1").feedbackReport("Feedback-Type: abuse")
                .returnedHeaders("Message-ID: " + NUESTRO_ID).build();
        IncomingMessage baja = mensaje("u1").from("ana@clinica.example").inReplyTo(NUESTRO_ID)
                .bodyText("Por favor no me escriban más").build();

        SyncResult r = service.process(List.of(queja, baja));

        assertThat(r.complaints()).isEqualTo(1);
        assertThat(r.unsubscribes()).isEqualTo(1);
        verify(bloqueo).block(envio, "ana@clinica.example", SuppressionReason.COMPLAINT);
        verify(bloqueo).block(envio, "ana@clinica.example", SuppressionReason.UNSUBSCRIBE);
        // La baja por respuesta usa el mismo ID de evento que el enlace (5.5): cuenta una sola vez.
        ArgumentCaptor<EmailEventET> evento = ArgumentCaptor.forClass(EmailEventET.class);
        verify(eventos, org.mockito.Mockito.times(2)).save(evento.capture());
        assertThat(evento.getAllValues()).extracting(EmailEventET::getProviderId).contains("unsubscribe:41");
    }

    @Test
    void fueraDeLaOficinaNoCuentaComoRespuesta() {
        SyncResult r = service.process(List.of(mensaje("a1").inReplyTo(NUESTRO_ID)
                .headers(Map.of("auto-submitted", "auto-replied")).bodyText("Estoy de vacaciones").build()));

        assertThat(r.autoReplies()).isEqualTo(1);
        assertThat(guardados).isEmpty();
    }

    @Test
    void unMensajeQueFallaNoDetieneALosDemas() {
        when(cola.findById(99)).thenThrow(new IllegalStateException("base caída"));
        IncomingMessage malo = mensaje("x").inReplyTo("<pia.99.abcdefabcdef@p.example>").bodyText("hola").build();
        IncomingMessage bueno = mensaje("y").inReplyTo(NUESTRO_ID).bodyText("hola").build();

        SyncResult r = service.process(List.of(malo, bueno));

        assertThat(r.read()).isEqualTo(2);
        assertThat(r.replies()).isEqualTo(1);
    }

    @Test
    void sinBuzonOSinLlaveNoLeeNada() {
        when(buzon.getIfAvailable()).thenReturn(null);
        assertThat(service.syncMailbox()).isEqualTo(SyncResult.EMPTY);

        MailboxPort puerto = mock(MailboxPort.class);
        when(puerto.requiresMailboxKey()).thenReturn(true); // Gmail o Graph
        when(buzon.getIfAvailable()).thenReturn(puerto);
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(new TenantConfigET()));
        assertThat(service.syncMailbox()).isEqualTo(SyncResult.EMPTY);
        verify(puerto, never()).readInbox(any(), any());
    }

    @Test
    void conSmtpLeeAunqueLaConfiguracionNoTengaBuzon() {
        MailboxPort puerto = mock(MailboxPort.class);
        when(puerto.requiresMailboxKey()).thenReturn(false); // SMTP con Mailpit
        when(buzon.getIfAvailable()).thenReturn(puerto);
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(new TenantConfigET()));
        when(puerto.readInbox(any(), any())).thenReturn(List.of());

        service.syncMailbox();

        verify(puerto).readInbox(eq(null), any());
    }

    @Test
    void conBuzonLeeLaVentanaDeHorasConfigurada() {
        MailboxPort puerto = mock(MailboxPort.class);
        when(buzon.getIfAvailable()).thenReturn(puerto);
        TenantConfigET config = new TenantConfigET();
        config.setMailboxSsmKey("/config/buzon");
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        properties.getMailbox().setLookbackHours(48);
        when(puerto.readInbox(eq("/config/buzon"), any())).thenReturn(List.of());

        service.syncMailbox();

        verify(puerto).readInbox("/config/buzon", LocalDateTime.of(2026, 11, 22, 15, 0));
    }

    @Test
    void laAperturaSeRegistraSoloLaPrimeraVez() {
        when(eventos.existsByProviderId("open:41")).thenReturn(false, true);

        assertThat(service.registerOpen(41)).isTrue();
        assertThat(service.registerOpen(41)).isFalse();
        assertThat(tiposGuardados()).containsExactly(EmailEventType.OPEN);
    }
}
