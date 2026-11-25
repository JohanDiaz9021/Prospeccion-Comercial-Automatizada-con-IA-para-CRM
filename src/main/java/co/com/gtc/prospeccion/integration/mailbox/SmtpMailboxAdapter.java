package co.com.gtc.prospeccion.integration.mailbox;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Smtp;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

/**
 * Buzón por SMTP (tarea 5.2), pensado para Mailpit en local: Mailpit atrapa cada correo y nunca lo entrega, y se ven en
 * http://localhost:8025. Si está prospeccion.mailbox.smtp.mailpit-api-url, también lee de Mailpit lo que llegó al
 * buzón de prospección (respuestas, rebotes y quejas que se le envíen), así se prueba el seguimiento (5.4) de punta a
 * punta.
 * <p>
 * Por seguridad no arranca con un servidor que no sea local, salvo smtp.allow-remote-host = true a propósito.
 */
@Component
@ConditionalOnProperty(name = "prospeccion.mailbox.provider", havingValue = "smtp")
public class SmtpMailboxAdapter implements MailboxPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailboxAdapter.class);
    private static final String TIMEOUT_MS = "20000";
    /** Mensajes de Mailpit que se revisan por lectura (los más recientes primero). */
    static final int MAILPIT_PAGE = 500;

    private final Smtp smtp;
    private final String fromAddress;
    private final ObjectMapper objectMapper;
    private final Session session;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SmtpMailboxAdapter(ProspeccionProperties properties, ObjectMapper objectMapper) {
        this.smtp = properties.getMailbox().getSmtp();
        this.fromAddress = properties.getMailbox().getFromAddress();
        this.objectMapper = objectMapper;
        if (smtp.getHost() == null || smtp.getHost().isBlank()) {
            throw new IllegalStateException("prospeccion.mailbox.provider = smtp exige SMTP_HOST (en local: localhost, "
                    + "con Mailpit levantado)");
        }
        if (!smtp.isAllowRemoteHost() && !isLocal(smtp.getHost())) {
            throw new IllegalStateException("SMTP_HOST no es local: por seguridad el envío por SMTP es solo para Mailpit."
                    + " Para otro servidor, SMTP_ALLOW_REMOTE_HOST=true a propósito.");
        }
        this.session = Session.getInstance(sessionProperties(smtp));
        log.info("Buzón SMTP: {}:{}{}", smtp.getHost(), smtp.getPort(),
                isBlank(smtp.getMailpitApiUrl()) ? " (sin lectura del buzón)" : ", lectura por la API de Mailpit");
    }

    @Override
    public String send(OutgoingEmail email) {
        MimeMessage message = MimeEmails.build(session, email);
        try {
            if (isBlank(smtp.getUsername())) {
                Transport.send(message);
            } else {
                Transport.send(message, smtp.getUsername(), smtp.getPassword());
            }
            return email.getMessageId();
        } catch (SendFailedException e) {
            // El servidor rechazó la dirección: reintentar no sirve.
            boolean rejected = e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0;
            throw new MailboxSendException("El servidor SMTP rechazó el correo (" + firstLine(e) + ")", rejected, e);
        } catch (MessagingException e) {
            throw new MailboxSendException("No se pudo entregar al servidor SMTP (" + firstLine(e) + ")", false, e);
        }
    }

    @Override
    public boolean requiresMailboxKey() {
        return false;
    }

    /**
     * <b>Solo herramientas locales.</b> Entrega un mensaje ya armado (una respuesta o un rebote simulados dirigidos al
     * buzón de prospección), para leerlo de vuelta desde Mailpit.
     */
    public void deliver(MimeMessage message) {
        try {
            message.saveChanges();
            Transport transport = session.getTransport("smtp");
            try {
                if (isBlank(smtp.getUsername())) {
                    transport.connect();
                } else {
                    transport.connect(smtp.getUsername(), smtp.getPassword());
                }
                transport.sendMessage(message, message.getAllRecipients());
            } finally {
                transport.close();
            }
        } catch (MessagingException e) {
            throw new MailboxSendException("No se pudo entregar al servidor SMTP (" + firstLine(e) + ")", false, e);
        }
    }

    public String mailboxAddress() {
        return fromAddress;
    }

    /**
     * Lo que llegó al buzón de prospección desde sinceUtc, leído de la API de Mailpit. Sin Mailpit no hay qué leer:
     * SMTP solo envía.
     */
    @Override
    public List<IncomingMessage> readInbox(String mailboxKey, LocalDateTime sinceUtc) {
        if (isBlank(smtp.getMailpitApiUrl())) {
            log.debug("SMTP sin MAILPIT_API_URL: no hay buzón que leer");
            return List.of();
        }
        if (isBlank(fromAddress)) {
            log.warn("Falta MAILBOX_FROM_ADDRESS: no se sabe qué mensajes de Mailpit son para el buzón de prospección");
            return List.of();
        }
        String base = smtp.getMailpitApiUrl().replaceAll("/+$", "");
        String buzon = fromAddress.strip().toLowerCase(Locale.ROOT);
        List<IncomingMessage> result = new ArrayList<>();
        JsonNode list = getJson(base + "/api/v1/messages?limit=" + MAILPIT_PAGE);
        for (JsonNode summary : list.path("messages")) {
            if (!addressedTo(summary, buzon)) {
                continue; // Los correos que el robot envió van a los contactos, no al buzón.
            }
            LocalDateTime created = parseCreated(summary.path("Created").asText(null));
            if (created != null && sinceUtc != null && created.isBefore(sinceUtc)) {
                continue;
            }
            String id = summary.path("ID").asText();
            byte[] raw = getBytes(base + "/api/v1/message/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/raw");
            result.add(MimeEmails.parse(raw, "mailpit-" + id, null, created));
        }
        return result;
    }

    static boolean addressedTo(JsonNode summary, String mailbox) {
        for (String field : List.of("To", "Cc", "Bcc")) {
            for (JsonNode address : summary.path(field)) {
                if (mailbox.equalsIgnoreCase(address.path("Address").asText("").strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    static LocalDateTime parseCreated(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode getJson(String url) {
        try {
            return objectMapper.readTree(getBytes(url));
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit respondió algo que no es JSON", e);
        }
    }

    private byte[] getBytes(String url) {
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit respondió " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer Mailpit (" + e.getClass().getSimpleName()
                    + "). ¿Está levantado? docker compose -f docker-compose.local.yml --profile correo up -d mailpit", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lectura de Mailpit interrumpida", e);
        }
    }

    private static Properties sessionProperties(Smtp smtp) {
        Properties p = new Properties();
        p.put("mail.smtp.host", smtp.getHost().strip());
        p.put("mail.smtp.port", String.valueOf(smtp.getPort()));
        p.put("mail.smtp.auth", String.valueOf(!isBlank(smtp.getUsername())));
        p.put("mail.smtp.starttls.enable", String.valueOf(smtp.isStarttls()));
        p.put("mail.smtp.starttls.required", String.valueOf(smtp.isStarttls()));
        p.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        p.put("mail.smtp.timeout", TIMEOUT_MS);
        p.put("mail.smtp.writetimeout", TIMEOUT_MS);
        return p;
    }

    /**
     * localhost, 127.x, ::1 o los nombres de Mailpit en Docker. Una IP de la red interna NO cuenta: podría ser un
     * servidor de correo real.
     */
    static boolean isLocal(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("mailpit") || h.equals("host.docker.internal")) {
            return true;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        String line = message.lines().findFirst().orElse("").strip();
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
