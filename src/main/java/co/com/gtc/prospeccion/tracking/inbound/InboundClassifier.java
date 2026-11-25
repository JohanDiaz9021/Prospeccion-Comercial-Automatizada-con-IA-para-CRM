package co.com.gtc.prospeccion.tracking.inbound;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;

/**
 * Tarea 5.4: qué es cada mensaje que llega al buzón de prospección, solo por su contenido y encabezados (igual con
 * Gmail o Microsoft 365). No consulta la base: asociar el mensaje a su envío lo hace TrackingService.
 * <p>
 * Orden: queja, rebote, respuesta automática, pedido de baja escrito a mano y, si no es nada de eso, respuesta.
 */
public final class InboundClassifier {

    public enum Kind {
        /** Una persona contestó. */
        REPLY,
        /** Contestó pidiendo que no le escriban más: es una baja, igual que el enlace (5.5). */
        UNSUBSCRIBE_REQUEST,
        /** "Fuera de la oficina" y similares: no cuenta como respuesta. */
        AUTO_REPLY,
        /** El buzón no existe o rechaza el correo para siempre (código 5.x.x). */
        HARD_BOUNCE,
        /** Falla temporal (buzón lleno, servidor caído; código 4.x.x). */
        SOFT_BOUNCE,
        /** La persona lo marcó como spam (reporte ARF del proveedor). */
        COMPLAINT
    }

    /**
     * @param bouncedRecipient destinatario que rebotó (Final-Recipient), si es un rebote
     * @param status           código del rebote (por ejemplo 5.1.1), si viene
     */
    public record Classification(Kind kind, String bouncedRecipient, String status) {
    }

    private static final Pattern STATUS = Pattern.compile("(?im)^\\s*Status:\\s*([245]\\.\\d{1,3}\\.\\d{1,3})");
    private static final Pattern ACTION = Pattern.compile("(?im)^\\s*Action:\\s*([a-z]+)");
    private static final Pattern RECIPIENT = Pattern.compile(
            "(?im)^\\s*(?:Final|Original)-Recipient:\\s*(?:rfc822;)?\\s*<?([^\\s<>;]+@[^\\s<>;]+)>?");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern ENHANCED_CODE = Pattern.compile("\\b([45])\\.\\d{1,3}\\.\\d{1,3}\\b");

    /** Remitentes de los avisos de entrega fallida. */
    private static final List<String> MAILER = List.of("mailer-daemon", "postmaster", "mail delivery", "microsoftexchange");
    private static final List<String> BOUNCE_SUBJECT = List.of("undeliverable", "undelivered", "delivery status notification",
            "delivery failure", "mail delivery failed", "returned mail", "failure notice", "no se puede entregar",
            "no se pudo entregar", "entrega fallida", "devuelto");
    private static final List<String> HARD_TEXT = List.of("user unknown", "unknown user", "does not exist", "doesn't exist",
            "no such user", "mailbox unavailable", "mailbox not found", "address rejected", "recipient rejected",
            "invalid recipient", "account disabled", "account has been disabled", "no existe", "usuario desconocido",
            "direccion no encontrada", "address not found");
    private static final List<String> AUTO_SUBJECT = List.of("respuesta automatica", "automatic reply", "auto reply",
            "autoreply", "auto-reply", "out of office", "out of the office", "fuera de la oficina", "fuera de oficina",
            "ausencia", "estoy de vacaciones", "on vacation");
    private static final List<String> UNSUBSCRIBE_TEXT = List.of("darme de baja", "dar de baja", "de baja por favor",
            "no me escriban", "no me escriba", "no me envien", "no me envie", "no deseo recibir", "no quiero recibir",
            "dejen de enviar", "deje de enviar", "dejen de escribir", "deje de escribirme", "eliminen mi correo",
            "eliminar mi correo", "borren mi correo", "sacarme de su lista", "sacarme de la lista", "quitarme de su lista",
            "quitenme de su lista", "remove me", "unsubscribe", "stop emailing", "stop sending", "opt out", "opt-out");
    /** Donde empieza el texto citado de una respuesta: lo de abajo es nuestro correo, no lo que escribió la persona. */
    private static final Pattern QUOTE_START = Pattern.compile("(?im)^(>|-{2,}\\s*(original message|mensaje original)"
            + "|_{5,}|(de|from):\\s.+@|(el|on)\\s.{0,200}(escribio|wrote):?\\s*$)");

    private InboundClassifier() {
    }

    public static Classification classify(IncomingMessage m) {
        Map<String, String> headers = m.getHeaders() == null ? Map.of() : m.getHeaders();
        String contentType = lower(headers.get("content-type"));
        String subject = plain(m.getSubject());
        String body = m.getBodyText() == null ? "" : m.getBodyText();

        if (m.getFeedbackReport() != null || contentType.contains("report-type=feedback-report")) {
            return new Classification(Kind.COMPLAINT, recipient(m.getFeedbackReport(), body), null);
        }
        boolean fromMailer = MAILER.stream().anyMatch(lower(m.getFrom())::contains);
        if (m.getDeliveryStatus() != null || contentType.contains("report-type=delivery-status")
                || fromMailer && BOUNCE_SUBJECT.stream().anyMatch(subject::contains)) {
            return bounce(m.getDeliveryStatus(), body);
        }
        if (isAutoReply(headers, subject)) {
            return new Classification(Kind.AUTO_REPLY, null, null);
        }
        String written = plain(newText(body));
        if (UNSUBSCRIBE_TEXT.stream().anyMatch(written::contains)
                || subject.matches("\\s*(re:\\s*)?(baja|unsubscribe|remove)\\s*")) {
            return new Classification(Kind.UNSUBSCRIBE_REQUEST, null, null);
        }
        return new Classification(Kind.REPLY, null, null);
    }

    /** Duro con código 5.x.x o, sin código, con un texto de buzón inexistente; temporal en cualquier otro caso. */
    private static Classification bounce(String deliveryStatus, String body) {
        String report = deliveryStatus != null ? deliveryStatus : body;
        String status = first(STATUS, report);
        if (status == null) {
            status = first(ENHANCED_CODE, report) == null ? null : enhanced(report);
        }
        String action = lower(first(ACTION, report));
        String recipient = recipient(report, body);
        boolean hard;
        if ("delayed".equals(action)) {
            hard = false;
        } else if (status != null) {
            hard = status.startsWith("5");
        } else {
            String text = plain(report);
            hard = HARD_TEXT.stream().anyMatch(text::contains) || text.contains(" 550 ") || text.startsWith("550 ");
        }
        return new Classification(hard ? Kind.HARD_BOUNCE : Kind.SOFT_BOUNCE, recipient, status);
    }

    private static boolean isAutoReply(Map<String, String> headers, String subject) {
        String autoSubmitted = lower(headers.get("auto-submitted"));
        if (!autoSubmitted.isEmpty() && !"no".equals(autoSubmitted)) {
            return true;
        }
        if (headers.containsKey("x-autoreply") || headers.containsKey("x-autorespond")) {
            return true;
        }
        String precedence = lower(headers.get("precedence"));
        if (List.of("auto_reply", "bulk", "junk", "list").contains(precedence)) {
            return true;
        }
        return AUTO_SUBJECT.stream().anyMatch(subject::contains);
    }

    /** Lo que escribió la persona: el texto antes de la cita de nuestro correo (máximo 2.000 caracteres). */
    static String newText(String body) {
        if (body == null) {
            return "";
        }
        Matcher m = QUOTE_START.matcher(plain(body));
        String text = m.find() ? body.substring(0, Math.min(m.start(), body.length())) : body;
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static String recipient(String... texts) {
        for (String text : texts) {
            String r = first(RECIPIENT, text);
            if (r != null) {
                return r.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String enhanced(String text) {
        Matcher m = Pattern.compile("\\b([45]\\.\\d{1,3}\\.\\d{1,3})\\b").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Dirección de correo de un remitente "Nombre <correo>", en minúsculas. */
    public static String address(String from) {
        String r = first(EMAIL, from);
        return r == null ? null : r.toLowerCase(Locale.ROOT);
    }

    private static String first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /** Minúsculas y sin tildes, para comparar frases en español. */
    private static String plain(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
