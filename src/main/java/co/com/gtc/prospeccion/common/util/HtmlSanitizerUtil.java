package co.com.gtc.prospeccion.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Limpia el HTML de los borradores (generados por Gemini o editados por una persona) con owasp-java-html-sanitizer
 * antes de guardarlos o enviarlos, y saca su versión en texto plano.
 * <p>
 * Solo deja párrafos, saltos de línea, listas, negrita, cursiva y enlaces https o mailto (con rel="nofollow").
 * Quita scripts, estilos, imágenes, formularios, atributos y cualquier otra etiqueta (su texto se conserva).
 */
public final class HtmlSanitizerUtil {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "ul", "ol", "li", "strong", "b", "em", "i", "a")
            .allowAttributes("href").onElements("a")
            .allowUrlProtocols("https", "mailto")
            .requireRelNofollowOnLinks()
            .toFactory();

    private static final Pattern BLOCK_END = Pattern.compile("(?i)</p>|</ul>|</ol>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>|</li>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li>");
    private static final Pattern LINK = Pattern.compile("(?i)<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ");

    private HtmlSanitizerUtil() {
    }

    /** HTML limpio y sin espacios sobrantes; cadena vacía si no viene nada. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return POLICY.sanitize(html).strip();
    }

    /**
     * Texto plano para la parte text/plain del correo y para contar palabras: un párrafo por bloque, las viñetas como
     * "- " y los enlaces como "texto (dirección)". Recibe HTML ya limpio.
     */
    public static String toPlainText(String sanitizedHtml) {
        if (sanitizedHtml == null || sanitizedHtml.isBlank()) {
            return "";
        }
        String text = sanitizedHtml.replaceAll("\\s*\\R\\s*", " ");
        text = LINK.matcher(text).replaceAll(match -> {
            String label = match.group(2);
            String href = match.group(1);
            return Matcher.quoteReplacement(TAG.matcher(label).replaceAll("").equals(decode(href))
                    ? label
                    : label + " (" + href + ")");
        });
        text = LIST_ITEM.matcher(text).replaceAll("- ");
        text = LINE_BREAK.matcher(text).replaceAll("\n");
        text = BLOCK_END.matcher(text).replaceAll("\n\n");
        text = TAG.matcher(text).replaceAll("");
        text = decode(text);
        return text.replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** Convierte &amp;, &#64;, &#x40;... en el carácter (el sanitizador codifica algunos signos). */
    static String decode(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String value;
            try {
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    value = Character.toString(Integer.parseInt(entity.substring(2), 16));
                } else if (entity.startsWith("#")) {
                    value = Character.toString(Integer.parseInt(entity.substring(1)));
                } else {
                    value = NAMED_ENTITIES.getOrDefault(entity.toLowerCase(), matcher.group());
                }
            } catch (IllegalArgumentException e) { // número fuera de rango: se deja como venía
                value = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
