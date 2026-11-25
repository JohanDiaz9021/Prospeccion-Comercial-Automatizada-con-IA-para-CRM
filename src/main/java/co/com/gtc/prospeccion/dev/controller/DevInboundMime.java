package co.com.gtc.prospeccion.dev.controller;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import co.com.gtc.prospeccion.integration.mailbox.MimeEmails;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * <b>Solo perfil local.</b> Convierte un mensaje simulado (respuesta, rebote DSN o queja ARF) en un correo MIME de
 * verdad, como los que mandan Gmail y Outlook, para enviarlo a Mailpit y leerlo de vuelta por el mismo camino que el
 * buzón real (MimeEmails.parse).
 */
final class DevInboundMime {

    private DevInboundMime() {
    }

    static MimeMessage build(IncomingMessage simulated, String mailbox) throws MessagingException {
        MimeMessage m = new MimeMessage(MimeEmails.offlineSession()) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", simulated.getInternetMessageId());
            }
        };
        m.setFrom(new InternetAddress(simulated.getFrom()));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress(mailbox));
        m.setSubject(simulated.getSubject(), "UTF-8");
        m.setSentDate(new Date());
        if (simulated.getInReplyTo() != null) {
            m.setHeader("In-Reply-To", simulated.getInReplyTo());
        }
        if (simulated.getReferences() != null && !simulated.getReferences().isEmpty()) {
            m.setHeader("References", String.join(" ", simulated.getReferences()));
        }
        Map<String, String> headers = simulated.getHeaders() == null ? Map.of() : simulated.getHeaders();
        headers.forEach((name, value) -> {
            if (!"content-type".equals(name.toLowerCase(Locale.ROOT))) {
                try {
                    m.setHeader(name, value);
                } catch (MessagingException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        String text = simulated.getBodyText() == null ? "" : simulated.getBodyText();
        if (simulated.getDeliveryStatus() != null) {
            m.setContent(report("delivery-status", text, "message/delivery-status", simulated.getDeliveryStatus(),
                    simulated.getReturnedHeaders()));
        } else if (simulated.getFeedbackReport() != null) {
            m.setContent(report("feedback-report", text.isEmpty() ? "Reporte de queja." : text,
                    "message/feedback-report", simulated.getFeedbackReport(), simulated.getReturnedHeaders()));
        } else {
            m.setText(text, "UTF-8");
        }
        m.saveChanges();
        return m;
    }

    /** multipart/report (RFC 6522): explicación, reporte y encabezados del correo original. */
    private static MimeMultipart report(String reportType, String text, String reportMime, String report,
            String returnedHeaders) throws MessagingException {
        MimeMultipart multipart = new MimeMultipart("report; report-type=" + reportType);
        MimeBodyPart explanation = new MimeBodyPart();
        explanation.setText(text, "UTF-8");
        multipart.addBodyPart(explanation);
        multipart.addBodyPart(raw(report, reportMime));
        if (returnedHeaders != null) {
            multipart.addBodyPart(raw(returnedHeaders, "text/rfc822-headers"));
        }
        return multipart;
    }

    private static MimeBodyPart raw(String content, String mime) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(
                content.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), mime)));
        part.setHeader("Content-Type", mime);
        part.setHeader("Content-Transfer-Encoding", "7bit");
        return part;
    }
}
