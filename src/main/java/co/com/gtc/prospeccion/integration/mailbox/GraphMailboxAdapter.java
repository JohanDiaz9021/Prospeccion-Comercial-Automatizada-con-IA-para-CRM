package co.com.gtc.prospeccion.integration.mailbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;

/**
 * Buzón por Microsoft Graph. Vacío: la dependencia del proveedor se agrega cuando se decida.
 */
@Component
@ConditionalOnProperty(name = "prospeccion.mailbox.provider", havingValue = "graph")
public class GraphMailboxAdapter implements MailboxPort {

    @Override
    public String send(OutgoingEmail email) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public List<IncomingMessage> readInbox(String mailboxKey, LocalDateTime sinceUtc) {
        // TODO 5.2/5.4: leer los mensajes recibidos desde sinceUtc con sus encabezados, el texto y las partes
        //       message/delivery-status, message/feedback-report y los encabezados del correo original.
        throw new UnsupportedOperationException("TODO");
    }
}
