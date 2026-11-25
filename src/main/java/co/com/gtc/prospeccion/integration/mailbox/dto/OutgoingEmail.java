package co.com.gtc.prospeccion.integration.mailbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Correo listo para enviar, ya armado por el sistema (EmailComposer, tarea 5.2): cuerpo con el pie legal, enlace de
 * baja y Message-ID propio. El adaptador solo lo convierte en MIME (MimeEmails.build) y lo entrega al proveedor.
 * No registrar en logs: trae el correo y el texto de una persona.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutgoingEmail {

    /** la_tenantconfig.MAILBOX_SSM_KEY: credenciales del buzón en SSM (Gmail o Graph; SMTP no lo usa). */
    private String mailboxKey;
    private String fromAddress;
    private String fromName;
    private String to;
    private String subject;
    private String bodyHtml;
    private String bodyText;
    /** Va en los encabezados List-Unsubscribe y List-Unsubscribe-Post (baja en un clic). */
    private String unsubscribeUrl;
    /** Encabezado Message-ID de {@link co.com.gtc.prospeccion.integration.mailbox.MessageIds#forSend}. */
    private String messageId;
    private String threadId;
}
