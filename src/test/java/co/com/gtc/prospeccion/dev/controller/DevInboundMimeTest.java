package co.com.gtc.prospeccion.dev.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.integration.mailbox.MessageIds;
import co.com.gtc.prospeccion.integration.mailbox.MimeEmails;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Kind;

/** Cada caso simulado, al viajar como MIME real (por Mailpit), se lee y se clasifica igual que si fuera directo. */
class DevInboundMimeTest {

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = { "REPLY", "UNSUBSCRIBE_REQUEST", "AUTO_REPLY", "HARD_BOUNCE", "SOFT_BOUNCE",
            "COMPLAINT" })
    void elMensajeSimuladoSobreviveAlViajeComoMime(Kind kind) throws Exception {
        SendQueueET send = new SendQueueET();
        ReflectionTestUtils.setField(send, "id", 42);
        send.setRecipientEmail("ana.perez@clinica-demo.example");
        send.setProviderId(MessageIds.forSend(42, "gtc-prueba.local"));
        DevMailboxController controller = new DevMailboxController(null, null, null, null, null, null, null);
        IncomingMessage directo = controller.message(kind, send);

        byte[] raw = MimeEmails.toBytes(DevInboundMime.build(directo, "prospeccion@gtc-prueba.local"));
        IncomingMessage porBuzon = MimeEmails.parse(raw, "mailpit-1", null, null);

        assertThat(InboundClassifier.classify(porBuzon).kind()).isEqualTo(kind);
        assertThat(InboundClassifier.classify(porBuzon).kind()).isEqualTo(InboundClassifier.classify(directo).kind());
        assertThat(MessageIds.findSendId(porBuzon.getInReplyTo(), String.join(" ",
                porBuzon.getReferences()), porBuzon.getReturnedHeaders())).contains(42);
    }
}
