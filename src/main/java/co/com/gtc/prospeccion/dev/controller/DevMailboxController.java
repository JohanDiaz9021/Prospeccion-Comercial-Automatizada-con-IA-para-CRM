package co.com.gtc.prospeccion.dev.controller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.handoff.service.ReplyHandoffService;
import co.com.gtc.prospeccion.integration.mailbox.MessageIds;
import co.com.gtc.prospeccion.integration.mailbox.SmtpMailboxAdapter;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.service.SendQueueService;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Kind;
import co.com.gtc.prospeccion.tracking.service.TrackingService;
import co.com.gtc.prospeccion.tracking.service.TrackingService.SyncResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * <b>Solo perfil local.</b> Prueba el envío (5.2) y el seguimiento (5.4) sin buzón real: envía ya lo que está en cola
 * (con MAILBOX_PROVIDER=smtp va a Mailpit, que no entrega nada), lee el buzón, o marca un correo como enviado sin
 * enviarlo, y simula lo que llegaría al buzón (respuesta, rebote, queja...) con encabezados como los de Gmail y
 * Outlook. Con "porBuzon" lo simulado viaja como correo MIME real a Mailpit y se lee de vuelta: pasa por el mismo
 * código que lo que llegue del buzón real.
 */
@Profile("local")
@RestController
@PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
@RequestMapping("/api/dev/mailbox")
@Tag(name = "Herramientas locales")
public class DevMailboxController {

    static final String DOMINIO = "prospeccion.local";

    private final SendQueueRepository sendQueueRepository;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;
    private final TrackingService trackingService;
    private final SendQueueService sendQueueService;
    private final ObjectProvider<SmtpMailboxAdapter> smtp;
    private final ReplyHandoffService handoffService;
    private final Clock clock = Clock.systemUTC();

    public DevMailboxController(SendQueueRepository sendQueueRepository, MasterTableRepository masterTableRepository,
            DateTableResolver dateTableResolver, TrackingService trackingService, SendQueueService sendQueueService,
            ObjectProvider<SmtpMailboxAdapter> smtp, ReplyHandoffService handoffService) {
        this.sendQueueRepository = sendQueueRepository;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
        this.trackingService = trackingService;
        this.sendQueueService = sendQueueService;
        this.smtp = smtp;
        this.handoffService = handoffService;
    }

    /** Lo que pasó al leer: el seguimiento (5.4) y quién pasó al vendedor con su tarea (5.6). */
    public record ReadResult(SyncResult seguimiento, ReplyHandoffService.HandoffResult vendedor) {
    }

    /** porBuzon: el mensaje viaja por Mailpit (MAILBOX_PROVIDER=smtp) en lugar de procesarse directo. */
    public record SimulateRequest(@NotNull Kind kind, Boolean porBuzon) {
    }

    @PostMapping("/dispatch")
    @Operation(summary = "Envía ya los correos en cola, sin esperar su turno ni la ventana de la Ley 2300 (5.2)")
    public ResponseEntity<GeneralReponse> dispatch() {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, sendQueueService.dispatchNowIgnoringSchedule()));
    }

    @PostMapping("/sync")
    @Operation(summary = "Lee el buzón ya (con SMTP, desde Mailpit), procesa lo que llegó (5.4) y pasa al vendedor a quien respondió (5.6)")
    public ResponseEntity<GeneralReponse> sync() {
        SyncResult seguimiento = trackingService.syncMailbox();
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                new ReadResult(seguimiento, handoffService.handOffPending())));
    }

    @PostMapping("/drafts/{draftId}/mark-sent")
    @Operation(summary = "Marca como Enviado el correo de un borrador aprobado sin enviarlo (para probar sin Mailpit)")
    public ResponseEntity<GeneralReponse> markSent(@PathVariable Integer draftId) {
        SendQueueET send = sendOf(draftId);
        if (!SendQueueStatus.QUEUED.equals(send.getStatus().getCode())) {
            throw new ConflictException(MessageKeys.CONFLICT, "El correo del borrador " + draftId + " no está en cola");
        }
        send.setStatus(masterTableRepository.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.SENT)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de envío 602/2")));
        send.setSentAt(LocalDateTime.now(clock));
        send.setProviderId(MessageIds.forSend(send.getId(), DOMINIO));
        send.setThreadId("sim-hilo-" + send.getId());
        send.setLastUpdatedBy(send.getCreatedBy());
        send.setLastUpdatedDate(dateTableResolver.today());
        sendQueueRepository.save(send);
        trackingService.registerEvent(send.getId(), EmailEventType.SENT, "sent:" + send.getId(), send.getSentAt());
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                Map.of("sendQueueId", send.getId(), "messageId", send.getProviderId())));
    }

    @PostMapping("/drafts/{draftId}/simulate")
    @Operation(summary = "Simula un mensaje que llega al buzón por el correo de un borrador enviado y lo procesa")
    public ResponseEntity<GeneralReponse> simulate(@PathVariable Integer draftId, @Valid @RequestBody SimulateRequest request) {
        SendQueueET send = sendOf(draftId);
        if (!SendQueueStatus.SENT.equals(send.getStatus().getCode())) {
            throw new ConflictException(MessageKeys.CONFLICT,
                    "El correo del borrador " + draftId + " no se ha enviado: márquelo como enviado primero");
        }
        IncomingMessage simulated = message(request.kind(), send);
        if (Boolean.TRUE.equals(request.porBuzon())) {
            SmtpMailboxAdapter adapter = smtp.getIfAvailable();
            if (adapter == null) {
                throw new ConflictException(MessageKeys.CONFLICT,
                        "Simular por el buzón exige MAILBOX_PROVIDER=smtp con Mailpit levantado");
            }
            try {
                adapter.deliver(DevInboundMime.build(simulated, adapter.mailboxAddress()));
            } catch (jakarta.mail.MessagingException e) {
                throw new IllegalStateException("No se pudo armar el mensaje simulado", e);
            }
            // Mailpit lo guarda al recibirlo: se lee de una vez por el mismo camino que el buzón real.
            SyncResult seguimiento = trackingService.syncMailbox();
            return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                    new ReadResult(seguimiento, handoffService.handOffPending())));
        }
        SyncResult seguimiento = trackingService.process(List.of(simulated));
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                new ReadResult(seguimiento, handoffService.handOffPending())));
    }

    private SendQueueET sendOf(Integer draftId) {
        return sendQueueRepository.findByDraftId(draftId).orElseThrow(() -> new NotFoundException(MessageKeys.NOT_FOUND,
                "El borrador " + draftId + " no está en la cola de envío"));
    }

    /** Mensaje como lo entregaría Gmail o Graph para cada caso. Cada llamada es un mensaje nuevo. */
    IncomingMessage message(Kind kind, SendQueueET send) {
        String to = send.getRecipientEmail() == null ? "contacto@desconocido.example" : send.getRecipientEmail();
        String ourId = send.getProviderId();
        IncomingMessage.IncomingMessageBuilder m = IncomingMessage.builder()
                .providerMessageId("sim-" + UUID.randomUUID())
                .internetMessageId("<" + UUID.randomUUID() + "@simulado.example>")
                .receivedAt(LocalDateTime.now(clock));
        return switch (kind) {
            case REPLY -> m.from(to).subject("RE: su correo").inReplyTo(ourId).references(List.of(ourId))
                    .threadId(send.getThreadId()).headers(Map.of("content-type", "text/plain; charset=utf-8"))
                    .bodyText("Hola, me interesa. ¿Podemos hablar el jueves?\n\nEl lun, 24 nov 2026 a las 9:00, "
                            + "Comercial GTC escribió:\n> Buenos días...").build();
            case UNSUBSCRIBE_REQUEST -> m.from(to).subject("RE: su correo").inReplyTo(ourId).references(List.of(ourId))
                    .threadId(send.getThreadId()).headers(Map.of("content-type", "text/plain; charset=utf-8"))
                    .bodyText("Por favor darme de baja, no me escriban más.\n\nEl lun, 24 nov 2026 a las 9:00, "
                            + "Comercial GTC escribió:\n> Buenos días...").build();
            case AUTO_REPLY -> m.from(to).subject("Respuesta automática: su correo").inReplyTo(ourId)
                    .threadId(send.getThreadId()).headers(Map.of("auto-submitted", "auto-replied",
                            "content-type", "text/plain; charset=utf-8"))
                    .bodyText("Estoy fuera de la oficina hasta el lunes.").build();
            case HARD_BOUNCE -> bounce(m, to, ourId, "failed", "5.1.1", "550 5.1.1 User unknown");
            case SOFT_BOUNCE -> bounce(m, to, ourId, "failed", "4.2.2", "452 4.2.2 Mailbox full");
            case COMPLAINT -> m.from("feedback@proveedor.example").subject("Reporte de queja")
                    .headers(Map.of("content-type", "multipart/report; report-type=feedback-report"))
                    .feedbackReport("Feedback-Type: abuse\nUser-Agent: Simulado/1.0\nOriginal-Rcpt-To: " + to)
                    .returnedHeaders("Message-ID: " + ourId + "\nTo: " + to).build();
        };
    }

    private static IncomingMessage bounce(IncomingMessage.IncomingMessageBuilder m, String to, String ourId,
            String action, String status, String diagnostic) {
        return m.from("Mail Delivery Subsystem <mailer-daemon@googlemail.com>")
                .subject("Delivery Status Notification (Failure)")
                .headers(Map.of("content-type", "multipart/report; report-type=delivery-status"))
                .deliveryStatus("Reporting-MTA: dns; googlemail.com\n\nFinal-Recipient: rfc822; " + to
                        + "\nAction: " + action + "\nStatus: " + status + "\nDiagnostic-Code: smtp; " + diagnostic)
                .returnedHeaders("Message-ID: " + ourId + "\nTo: " + to)
                .bodyText("No se pudo entregar el mensaje a " + to + ".").build();
    }
}
