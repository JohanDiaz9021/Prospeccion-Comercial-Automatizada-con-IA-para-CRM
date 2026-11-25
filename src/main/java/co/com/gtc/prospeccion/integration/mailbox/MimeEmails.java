package co.com.gtc.prospeccion.integration.mailbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import co.com.gtc.prospeccion.common.util.HtmlSanitizerUtil;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;

/**
 * Parte común del correo (tarea 5.2), igual para SMTP, Gmail y Graph: los tres aceptan y entregan el mensaje en MIME.
 * <ul>
 * <li>{@link #build}: arma el correo que sale, con Message-ID propio, List-Unsubscribe de un clic y las versiones
 * texto y HTML.</li>
 * <li>{@link #parse}: lee un mensaje que llegó (respuesta, rebote DSN o queja ARF) y lo deja en
 * {@link IncomingMessage}, que es lo que clasifica el seguimiento (5.4).</li>
 * </ul>
 */
public final class MimeEmails {

    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
    /** Tamaño máximo que se lee de una parte: un rebote o una respuesta no necesitan más. */
    private static final int MAX_PART_BYTES = 256 * 1024;

    private MimeEmails() {
    }

    /** Sesión sin servidor: para armar o leer mensajes sin enviarlos. */
    public static Session offlineSession() {
        return Session.getInstance(new Properties());
    }

    /**
     * Mensaje MIME listo para enviar.
     *
     * @throws IllegalArgumentException si falta el remitente, el destinatario, el Message-ID o alguno no es válido
     */
    public static MimeMessage build(Session session, OutgoingEmail email) {
        if (isBlank(email.getFromAddress()) || isBlank(email.getTo()) || isBlank(email.getMessageId())) {
            throw new IllegalArgumentException("Faltan el remitente, el destinatario o el Message-ID del correo");
        }
        String messageId = email.getMessageId().strip();
        try {
            MimeMessage m = new MimeMessage(session) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    // El Message-ID es el nuestro (pia.<envío>...): así se reconocen sus respuestas y rebotes (5.4).
                    setHeader("Message-ID", messageId);
                }
            };
            m.setFrom(new InternetAddress(email.getFromAddress().strip(), email.getFromName(), "UTF-8"));
            m.setRecipient(Message.RecipientType.TO, strict(email.getTo()));
            m.setSubject(email.getSubject() == null ? "" : email.getSubject(), "UTF-8");
            m.setSentDate(new Date());
            if (!isBlank(email.getUnsubscribeUrl())) {
                // RFC 8058: Gmail y Outlook muestran "Cancelar suscripción" y dan de baja con un POST, sin abrir nada.
                m.setHeader("List-Unsubscribe", "<" + email.getUnsubscribeUrl().strip() + ">");
                m.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }

            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.getBodyText() == null ? "" : email.getBodyText(), "UTF-8");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(email.getBodyHtml() == null ? "" : email.getBodyHtml(), "UTF-8", "html");
            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(text);
            alternative.addBodyPart(html);
            m.setContent(alternative);
            m.saveChanges();
            return m;
        } catch (UnsupportedEncodingException | MessagingException e) {
            throw new IllegalArgumentException("No se pudo armar el correo: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Bytes del mensaje (RFC 822), para los proveedores que reciben el MIME crudo (Gmail "raw", Graph). */
    public static byte[] toBytes(MimeMessage message) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            message.writeTo(out);
            return out.toByteArray();
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("No se pudo convertir el correo a MIME", e);
        }
    }

    /**
     * Lee un mensaje que llegó al buzón.
     *
     * @param raw               mensaje completo (RFC 822)
     * @param providerMessageId ID en el proveedor: hace que cada mensaje se procese una sola vez
     * @param threadId          hilo en el proveedor, si lo da
     * @param receivedAtUtc     cuándo llegó según el proveedor; si es null se usa el encabezado Date
     */
    public static IncomingMessage parse(byte[] raw, String providerMessageId, String threadId, LocalDateTime receivedAtUtc) {
        try {
            MimeMessage m = new MimeMessage(offlineSession(), new ByteArrayInputStream(raw));
            Parts parts = new Parts();
            walk(m, parts, 0);
            String bodyText = parts.text != null ? parts.text
                    : parts.html != null ? HtmlSanitizerUtil.toPlainText(HtmlSanitizerUtil.sanitize(parts.html)) : null;
            return IncomingMessage.builder()
                    .providerMessageId(providerMessageId)
                    .threadId(threadId)
                    .internetMessageId(firstId(m.getHeader("Message-ID", null)))
                    .inReplyTo(firstId(m.getHeader("In-Reply-To", null)))
                    .references(ids(m.getHeader("References", " ")))
                    .from(decode(m.getHeader("From", null)))
                    .subject(m.getSubject())
                    .receivedAt(receivedAtUtc != null ? receivedAtUtc : utc(m.getSentDate()))
                    .headers(headers(m))
                    .bodyText(bodyText)
                    .deliveryStatus(parts.deliveryStatus)
                    .feedbackReport(parts.feedbackReport)
                    .returnedHeaders(parts.returnedHeaders)
                    .build();
        } catch (MessagingException | IOException e) {
            throw new IllegalArgumentException("El mensaje no es MIME válido: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Lo que interesa de cada parte del mensaje. */
    private static final class Parts {
        String text;
        String html;
        String deliveryStatus;
        String feedbackReport;
        String returnedHeaders;
    }

    private static void walk(Part part, Parts out, int depth) throws MessagingException, IOException {
        if (depth > 10) {
            return;
        }
        String type = baseType(part);
        if (type.startsWith("multipart/")) {
            Multipart multipart = new MimeMultipart(part.getDataHandler().getDataSource());
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), out, depth + 1);
            }
        } else if ("message/delivery-status".equals(type) || "message/global-delivery-status".equals(type)) {
            out.deliveryStatus = firstNonNull(out.deliveryStatus, read(part));
        } else if ("message/feedback-report".equals(type)) {
            out.feedbackReport = firstNonNull(out.feedbackReport, read(part));
        } else if ("text/rfc822-headers".equals(type) || "message/global-headers".equals(type)) {
            out.returnedHeaders = firstNonNull(out.returnedHeaders, read(part));
        } else if ("message/rfc822".equals(type) || "message/global".equals(type)) {
            // El correo original dentro de un rebote o una queja: solo sus encabezados (no su texto).
            out.returnedHeaders = firstNonNull(out.returnedHeaders, headerBlock(read(part)));
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return;
        } else if ("text/plain".equals(type)) {
            out.text = firstNonNull(out.text, read(part));
        } else if ("text/html".equals(type)) {
            out.html = firstNonNull(out.html, read(part));
        }
    }

    private static String baseType(Part part) throws MessagingException {
        String contentType = part.getContentType();
        if (contentType == null) {
            return "text/plain";
        }
        try {
            return new ContentType(contentType).getBaseType().toLowerCase(Locale.ROOT);
        } catch (ParseException e) {
            return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        }
    }

    /** Contenido de la parte ya decodificado (base64 o quoted-printable) en su juego de caracteres. */
    private static String read(Part part) throws MessagingException, IOException {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readNBytes(MAX_PART_BYTES);
        }
        return new String(bytes, charset(part)).replace("\r\n", "\n");
    }

    private static Charset charset(Part part) {
        try {
            String name = new ContentType(part.getContentType()).getParameter("charset");
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(MimeUtility.javaCharset(name));
        } catch (RuntimeException | MessagingException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Encabezados de un correo en texto: todo hasta la primera línea en blanco. */
    private static String headerBlock(String message) {
        int end = message.indexOf("\n\n");
        return (end < 0 ? message : message.substring(0, end)).strip();
    }

    /** Encabezados con el nombre en minúsculas y el valor decodificado (el primero si se repite). */
    private static Map<String, String> headers(MimeMessage m) throws MessagingException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<Header> all = m.getAllHeaders();
        while (all.hasMoreElements()) {
            Header h = all.nextElement();
            headers.putIfAbsent(h.getName().toLowerCase(Locale.ROOT), decode(h.getValue()));
        }
        return headers;
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(MimeUtility.unfold(value)).strip();
        } catch (UnsupportedEncodingException e) {
            return value.strip();
        }
    }

    private static String firstId(String value) {
        List<String> ids = ids(value);
        return ids.isEmpty() ? (isBlank(value) ? null : value.strip()) : ids.get(0);
    }

    private static List<String> ids(String value) {
        List<String> ids = new ArrayList<>();
        if (value != null) {
            Matcher matcher = MESSAGE_ID.matcher(value);
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    private static InternetAddress strict(String address) {
        try {
            InternetAddress parsed = new InternetAddress(address.strip(), true);
            parsed.validate();
            return parsed;
        } catch (AddressException e) {
            throw new IllegalArgumentException("El destinatario no es una dirección válida", e);
        }
    }

    private static LocalDateTime utc(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : candidate;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
