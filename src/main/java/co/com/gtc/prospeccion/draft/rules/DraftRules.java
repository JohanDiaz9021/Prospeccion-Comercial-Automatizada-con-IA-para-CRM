package co.com.gtc.prospeccion.draft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revisión en código del borrador que redacta Gemini (tarea 4.3), con las reglas de docs/negocio/mensaje-base.md.
 * <ul>
 * <li><b>Errores:</b> el borrador no sirve (sin asunto o cuerpo, marcas sin llenar, sin la firma). Se pide otra
 * redacción y, si vuelve a fallar, no se guarda.</li>
 * <li><b>Avisos:</b> el borrador se guarda, pero quien lo revisa debe mirar ese punto (largo, asunto largo, números
 * que no vienen en los datos, exclamaciones, emojis, mayúsculas sostenidas).</li>
 * </ul>
 */
public final class DraftRules {

    public static final int MAX_SUBJECT_LENGTH = 60;
    /** Un asunto más largo que esto no es un asunto: se pide otra redacción. */
    public static final int HARD_MAX_SUBJECT_LENGTH = 150;
    public static final int MIN_WORDS = 90;
    public static final int MAX_WORDS = 160;

    /** Lo que se revisa. {@code blocking} = error (no se guarda); si no, aviso para quien revisa. */
    public enum Issue {
        EMPTY_SUBJECT(true),
        /** El asunto pasa de {@link DraftRules#HARD_MAX_SUBJECT_LENGTH} caracteres: no es un asunto. */
        SUBJECT_UNUSABLE(true),
        EMPTY_BODY(true),
        /** Quedó una marca {{...}} o [[...]] sin reemplazar. */
        MARKS_LEFT(true),
        /** No está la firma del mensaje base (su última línea). */
        SIGNATURE_MISSING(true),
        SUBJECT_TOO_LONG(false),
        TOO_SHORT(false),
        TOO_LONG(false),
        /** Hay un número que no está en el mensaje base ni en los datos: puede ser inventado. */
        NUMBER_NOT_IN_DATA(false),
        EXCLAMATIONS(false),
        EMOJI(false),
        SHOUTING(false);

        private final boolean blocking;

        Issue(boolean blocking) {
            this.blocking = blocking;
        }

        public boolean isBlocking() {
            return blocking;
        }
    }

    /** Resultado de la revisión. */
    public record Check(List<Issue> errors, List<Issue> warnings, int words) {

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    private static final Pattern MARKS = Pattern.compile("\\{\\{|}}|\\[\\[|]]|PERSONALIZAR:");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern EXCLAMATIONS = Pattern.compile("[!¡]{2,}");
    /** Dos o más palabras seguidas en mayúsculas ("MUY IMPORTANTE"). Siglas sueltas como ERP o GTC no cuentan. */
    private static final Pattern SHOUTING = Pattern.compile("(?U)\\b\\p{Lu}{3,}(?:\\s+\\p{Lu}{3,})+\\b");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");
    private static final Pattern REPLY_PREFIX = Pattern.compile("(?i)^((re|rv|fw|fwd)\\s*:\\s*)+");
    private static final Pattern SUBJECT_PREFIX = Pattern.compile("(?i)^asunto\\s*:\\s*");

    private DraftRules() {
    }

    /** Primera línea, sin "ASUNTO:" ni "RE:"/"RV:"/"FW:" falsos y sin espacios de más. */
    public static String cleanSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String line = subject.strip().lines().findFirst().orElse("");
        line = SUBJECT_PREFIX.matcher(line).replaceFirst("");
        line = REPLY_PREFIX.matcher(line).replaceFirst("");
        return line.replaceAll("\\s+", " ").strip();
    }

    /**
     * @param subject     asunto ya limpio ({@link #cleanSubject})
     * @param bodyText    cuerpo en texto plano (HtmlSanitizerUtil.toPlainText del HTML limpio)
     * @param baseMessage mensaje base con el que se redactó
     * @param knownData   todo lo que Gemini recibió además del mensaje base (datos del contacto y de la empresa),
     *                    para saber qué números vienen en los datos
     */
    public static Check check(String subject, String bodyText, String baseMessage, String knownData) {
        String safeSubject = subject == null ? "" : subject.strip();
        String body = bodyText == null ? "" : bodyText.strip();
        List<Issue> issues = new ArrayList<>();

        if (safeSubject.isEmpty()) {
            issues.add(Issue.EMPTY_SUBJECT);
        }
        if (body.isEmpty()) {
            issues.add(Issue.EMPTY_BODY);
        }
        if (MARKS.matcher(safeSubject).find() || MARKS.matcher(body).find()) {
            issues.add(Issue.MARKS_LEFT);
        }
        String signature = signatureOf(baseMessage);
        if (!body.isEmpty() && signature != null && !normalize(body).contains(normalize(signature))) {
            issues.add(Issue.SIGNATURE_MISSING);
        }
        if (safeSubject.length() > HARD_MAX_SUBJECT_LENGTH) {
            issues.add(Issue.SUBJECT_UNUSABLE);
        } else if (safeSubject.length() > MAX_SUBJECT_LENGTH) {
            issues.add(Issue.SUBJECT_TOO_LONG);
        }

        int words = countWords(body);
        if (!body.isEmpty() && words < MIN_WORDS) {
            issues.add(Issue.TOO_SHORT);
        } else if (words > MAX_WORDS) {
            issues.add(Issue.TOO_LONG);
        }

        String all = safeSubject + "\n" + body;
        if (hasUnknownNumber(all, (baseMessage == null ? "" : baseMessage) + "\n" + (knownData == null ? "" : knownData))) {
            issues.add(Issue.NUMBER_NOT_IN_DATA);
        }
        if (EXCLAMATIONS.matcher(all).find()) {
            issues.add(Issue.EXCLAMATIONS);
        }
        if (EMOJI.matcher(all).find()) {
            issues.add(Issue.EMOJI);
        }
        if (SHOUTING.matcher(all).find()) {
            issues.add(Issue.SHOUTING);
        }

        List<Issue> errors = issues.stream().filter(Issue::isBlocking).distinct().toList();
        List<Issue> warnings = issues.stream().filter(i -> !i.isBlocking()).distinct().toList();
        return new Check(errors, warnings, words);
    }

    /** Palabras del texto (las viñetas "-" no cuentan). */
    public static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String token : text.strip().split("\\s+")) {
            if (WORD.matcher(token).find()) {
                count++;
            }
        }
        return count;
    }

    /** Última línea con texto del mensaje base (la firma), si no tiene marcas. */
    static String signatureOf(String baseMessage) {
        if (baseMessage == null) {
            return null;
        }
        List<String> lines = baseMessage.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() < 2) {
            return null;
        }
        String last = lines.get(lines.size() - 1);
        return MARKS.matcher(last).find() || last.toLowerCase(Locale.ROOT).startsWith("asunto") ? null : last;
    }

    private static boolean hasUnknownNumber(String text, String allowedSource) {
        Set<String> allowed = new HashSet<>();
        Matcher known = NUMBER.matcher(allowedSource);
        while (known.find()) {
            allowed.add(stripZeros(known.group()));
        }
        Matcher used = NUMBER.matcher(text);
        while (used.find()) {
            if (!allowed.contains(stripZeros(used.group()))) {
                return true;
            }
        }
        return false;
    }

    private static String stripZeros(String number) {
        return number.replaceFirst("^0+(?=\\d)", "");
    }

    /** Minúsculas, sin puntuación y con un solo espacio, para comparar textos que Gemini pudo puntuar distinto. */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}¿¡]", " ").replaceAll("\\s+", " ").strip();
    }
}
