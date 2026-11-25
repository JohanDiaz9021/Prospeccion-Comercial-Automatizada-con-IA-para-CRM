package co.com.gtc.prospeccion.integration.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Kind;
import jakarta.mail.internet.MimeMessage;

class MimeEmailsTest {

    static final String NUESTRO_ID = "<pia.42.abc123def456@gtc-prueba.local>";

    static OutgoingEmail correo() {
        return OutgoingEmail.builder()
                .fromAddress("prospeccion@gtc-prueba.local")
                .fromName("Equipo Comercial GTC")
                .to("Ana.Perez@Clinica-Demo.example")
                .subject("Clínica Demo: menos procesos manuales")
                .bodyHtml("<p>Buenos días, Ana:</p><p>Le escribo por <strong>integración</strong>.</p>")
                .bodyText("Buenos días, Ana:\n\nLe escribo por integración.")
                .unsubscribeUrl("https://crm.example/msa-prospeccion/public/unsubscribe?t=abc.def")
                .messageId(NUESTRO_ID)
                .build();
    }

    @Test
    void elCorreoQueSaleLlevaNuestroMessageIdYLaBajaDeUnClic() throws Exception {
        MimeMessage m = MimeEmails.build(MimeEmails.offlineSession(), correo());
        String raw = new String(MimeEmails.toBytes(m), StandardCharsets.UTF_8);

        assertThat(m.getMessageID()).isEqualTo(NUESTRO_ID);
        assertThat(m.getHeader("List-Unsubscribe", null))
                .isEqualTo("<https://crm.example/msa-prospeccion/public/unsubscribe?t=abc.def>");
        assertThat(m.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(m.getSubject()).isEqualTo("Clínica Demo: menos procesos manuales");
        assertThat(raw).contains("multipart/alternative").contains("text/plain").contains("text/html");
        assertThat(raw).contains("From: Equipo Comercial GTC <prospeccion@gtc-prueba.local>");
    }

    @Test
    void loQueSaleSeLeeIgualDeVuelta() {
        byte[] raw = MimeEmails.toBytes(MimeEmails.build(MimeEmails.offlineSession(), correo()));

        IncomingMessage leido = MimeEmails.parse(raw, "p-1", "hilo-1", null);

        assertThat(leido.getInternetMessageId()).isEqualTo(NUESTRO_ID);
        assertThat(leido.getSubject()).isEqualTo("Clínica Demo: menos procesos manuales");
        assertThat(leido.getBodyText()).contains("Le escribo por integración.");
        assertThat(leido.getHeaders()).containsEntry("list-unsubscribe-post", "List-Unsubscribe=One-Click");
        assertThat(leido.getProviderMessageId()).isEqualTo("p-1");
        assertThat(leido.getThreadId()).isEqualTo("hilo-1");
        assertThat(leido.getReceivedAt()).isNotNull();
    }

    @Test
    void sinRemitenteDestinatarioOMessageIdNoSeArma() {
        OutgoingEmail sinId = correo();
        sinId.setMessageId(null);
        OutgoingEmail destinoMalo = correo();
        destinoMalo.setTo("esto no es un correo");

        assertThatThrownBy(() -> MimeEmails.build(MimeEmails.offlineSession(), sinId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MimeEmails.build(MimeEmails.offlineSession(), destinoMalo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaRespuestaDeOutlookEnQuotedPrintableSeReconoce() {
        String raw = """
                From: =?UTF-8?Q?Ana_P=C3=A9rez?= <ana.perez@clinica-demo.example>
                To: prospeccion@gtc-prueba.local
                Subject: =?UTF-8?Q?RE:_Cl=C3=ADnica_Demo?=
                Message-ID: <AM0PR01MB1234@outlook.example>
                In-Reply-To: <pia.42.abc123def456@gtc-prueba.local>
                References: <otro@x.example> <pia.42.abc123def456@gtc-prueba.local>
                Date: Tue, 24 Nov 2026 10:15:00 -0500
                MIME-Version: 1.0
                Content-Type: text/plain; charset="utf-8"
                Content-Transfer-Encoding: quoted-printable

                Hola, me interesa. =C2=BFPodemos hablar el jueves?
                """.replace("\n", "\r\n");

        IncomingMessage m = MimeEmails.parse(raw.getBytes(StandardCharsets.UTF_8), "o-1", null, null);

        assertThat(m.getFrom()).isEqualTo("Ana Pérez <ana.perez@clinica-demo.example>");
        assertThat(m.getSubject()).isEqualTo("RE: Clínica Demo");
        assertThat(m.getInReplyTo()).isEqualTo(NUESTRO_ID);
        assertThat(m.getReferences()).containsExactly("<otro@x.example>", NUESTRO_ID);
        assertThat(m.getBodyText()).contains("¿Podemos hablar el jueves?");
        assertThat(m.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 15, 15));
        assertThat(InboundClassifier.classify(m).kind()).isEqualTo(Kind.REPLY);
        assertThat(MessageIds.findSendId(m.getInReplyTo())).contains(42);
    }

    @Test
    void unReboteDeGmailTraeElReporteYElCorreoOriginal() {
        String raw = """
                From: Mail Delivery Subsystem <mailer-daemon@googlemail.com>
                To: prospeccion@gtc-prueba.local
                Subject: Delivery Status Notification (Failure)
                Message-ID: <bounce-1@mx.google.example>
                Date: Tue, 24 Nov 2026 10:20:00 +0000
                MIME-Version: 1.0
                Content-Type: multipart/report; report-type=delivery-status; boundary="LIMITE"

                --LIMITE
                Content-Type: text/plain; charset="UTF-8"

                No se pudo entregar el mensaje a ana.perez@clinica-demo.example.
                --LIMITE
                Content-Type: message/delivery-status

                Reporting-MTA: dns; googlemail.com

                Final-Recipient: rfc822; ana.perez@clinica-demo.example
                Action: failed
                Status: 5.1.1
                Diagnostic-Code: smtp; 550 5.1.1 The email account that you tried to reach does not exist.
                --LIMITE
                Content-Type: message/rfc822

                Message-ID: <pia.42.abc123def456@gtc-prueba.local>
                From: Equipo Comercial GTC <prospeccion@gtc-prueba.local>
                To: ana.perez@clinica-demo.example
                Subject: Clinica Demo

                Cuerpo del correo original que no debe quedar como texto del rebote.
                --LIMITE--
                """.replace("\n", "\r\n");

        IncomingMessage m = MimeEmails.parse(raw.getBytes(StandardCharsets.UTF_8), "g-1", null, null);

        assertThat(m.getDeliveryStatus()).contains("Status: 5.1.1").contains("Final-Recipient: rfc822;");
        assertThat(m.getReturnedHeaders()).contains("Message-ID: " + NUESTRO_ID).doesNotContain("Cuerpo del correo");
        assertThat(m.getBodyText()).startsWith("No se pudo entregar");
        InboundClassifier.Classification c = InboundClassifier.classify(m);
        assertThat(c.kind()).isEqualTo(Kind.HARD_BOUNCE);
        assertThat(c.bouncedRecipient()).isEqualTo("ana.perez@clinica-demo.example");
        assertThat(MessageIds.findSendId(m.getReturnedHeaders())).contains(42);
    }

    @Test
    void unaQuejaArfSeReconoce() {
        String raw = """
                From: feedback@proveedor.example
                To: prospeccion@gtc-prueba.local
                Subject: Reporte de queja
                Message-ID: <arf-1@proveedor.example>
                MIME-Version: 1.0
                Content-Type: multipart/report; report-type=feedback-report; boundary="ARF"

                --ARF
                Content-Type: text/plain

                Un usuario marco el correo como spam.
                --ARF
                Content-Type: message/feedback-report

                Feedback-Type: abuse
                User-Agent: Proveedor/1.0
                Version: 1
                Original-Rcpt-To: ana.perez@clinica-demo.example
                --ARF
                Content-Type: text/rfc822-headers

                Message-ID: <pia.42.abc123def456@gtc-prueba.local>
                To: ana.perez@clinica-demo.example
                --ARF--
                """.replace("\n", "\r\n");

        IncomingMessage m = MimeEmails.parse(raw.getBytes(StandardCharsets.UTF_8), "c-1", null, null);

        assertThat(m.getFeedbackReport()).contains("Feedback-Type: abuse");
        assertThat(m.getReturnedHeaders()).contains(NUESTRO_ID);
        assertThat(InboundClassifier.classify(m).kind()).isEqualTo(Kind.COMPLAINT);
    }

    @Test
    void unaRespuestaSoloEnHtmlSePasaATexto() {
        String raw = """
                From: ana.perez@clinica-demo.example
                To: prospeccion@gtc-prueba.local
                Subject: RE: hola
                In-Reply-To: <pia.42.abc123def456@gtc-prueba.local>
                MIME-Version: 1.0
                Content-Type: text/html; charset=utf-8

                <html><body><p>S&iacute;, hablemos.</p><script>alert(1)</script></body></html>
                """.replace("\n", "\r\n");

        IncomingMessage m = MimeEmails.parse(raw.getBytes(StandardCharsets.UTF_8), "h-1", null, null);

        assertThat(m.getBodyText()).contains("Sí, hablemos.").doesNotContain("alert");
    }

    @Test
    void loQueNoEsMimeNoRompeConUnErrorRaro() {
        IncomingMessage m = MimeEmails.parse("solo texto sin encabezados".getBytes(StandardCharsets.UTF_8), "x", null,
                null);

        assertThat(m.getProviderMessageId()).isEqualTo("x");
    }
}
