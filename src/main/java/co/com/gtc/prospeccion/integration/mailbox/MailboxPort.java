package co.com.gtc.prospeccion.integration.mailbox;

import java.time.LocalDateTime;
import java.util.List;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;

/**
 * Puerto del buzón corporativo. El proveedor (Gmail API o Microsoft Graph) aún no está decidido: se elige con
 * prospeccion.mailbox.provider. Mientras tanto, smtp envía a Mailpit en local (tarea 5.2).
 * <p>
 * El adaptador solo envía y lee. Reconocer respuestas, rebotes, quejas y respuestas automáticas lo hace el sistema
 * (tarea 5.4), igual para cualquier proveedor.
 */
public interface MailboxPort {

    /**
     * Envía el correo y devuelve el ID del mensaje en el proveedor. El correo lleva el encabezado Message-ID de
     * {@link MessageIds#forSend}, para reconocer después sus respuestas y rebotes. El MIME se arma con
     * {@link MimeEmails#build}, igual para cualquier proveedor.
     *
     * @throws MailboxSendException si el proveedor no lo aceptó (dice si vale la pena reintentar)
     */
    String send(OutgoingEmail email);

    /**
     * Si el adaptador necesita la_tenantconfig.MAILBOX_SSM_KEY (credenciales del buzón en SSM). Gmail y Graph sí; SMTP
     * toma su servidor de la configuración del servicio.
     */
    default boolean requiresMailboxKey() {
        return true;
    }

    /** Todo lo que llegó al buzón desde sinceUtc: respuestas, rebotes, quejas y respuestas automáticas. */
    List<IncomingMessage> readInbox(String mailboxKey, LocalDateTime sinceUtc);
}
