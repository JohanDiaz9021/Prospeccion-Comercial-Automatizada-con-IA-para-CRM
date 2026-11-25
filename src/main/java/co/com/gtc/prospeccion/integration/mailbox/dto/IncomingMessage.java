package co.com.gtc.prospeccion.integration.mailbox.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mensaje que llegó al buzón de prospección, tal como lo entrega el proveedor (Gmail API o Microsoft Graph). El
 * adaptador solo copia los datos; decidir si es respuesta, rebote o queja lo hace el sistema
 * (tracking.inbound.InboundClassifier), igual para cualquier proveedor.
 * No registrar en logs: trae correos y texto de personas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomingMessage {

    /** ID del mensaje en el proveedor: hace que cada mensaje se procese una sola vez. */
    private String providerMessageId;
    /** Hilo en el proveedor (threadId de Gmail, conversationId de Graph). */
    private String threadId;
    /** Encabezado Message-ID. */
    private String internetMessageId;
    /** Encabezado In-Reply-To. */
    private String inReplyTo;
    /** Encabezado References, un Message-ID por elemento. */
    private List<String> references;
    /** Remitente, con o sin nombre ("Ana <ana@empresa.co>"). */
    private String from;
    private String subject;
    /** UTC. */
    private LocalDateTime receivedAt;
    /** Encabezados con el nombre en minúsculas (content-type, auto-submitted, x-autoreply, precedence...). */
    private Map<String, String> headers;
    /** Texto plano del cuerpo (o el HTML pasado a texto). */
    private String bodyText;
    /** Parte message/delivery-status de un aviso de rebote (Final-Recipient, Action, Status...). */
    private String deliveryStatus;
    /** Parte message/feedback-report de una queja (formato ARF). */
    private String feedbackReport;
    /** Encabezados del correo original que vienen dentro de un rebote o una queja. */
    private String returnedHeaders;
}
