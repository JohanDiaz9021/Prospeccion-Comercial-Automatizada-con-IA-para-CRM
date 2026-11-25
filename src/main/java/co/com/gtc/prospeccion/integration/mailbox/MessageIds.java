package co.com.gtc.prospeccion.integration.mailbox;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message-ID propio de cada correo de prospección: {@code <pia.<sendQueueId>.<aleatorio>@<dominio>>}.
 * <p>
 * Las respuestas lo repiten en In-Reply-To y References, y los rebotes y quejas traen los encabezados del correo
 * original: así se sabe a qué envío pertenecen sin guardar nada más, con cualquier proveedor (tarea 5.4).
 */
public final class MessageIds {

    private static final Pattern ID = Pattern.compile("<pia\\.(\\d{1,10})\\.[A-Za-z0-9]{6,32}@[^>\\s]+>");
    private static final char[] ALFABETO = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom AZAR = new SecureRandom();

    private MessageIds() {
    }

    /** Message-ID para el envío; dominio = el del buzón de prospección. */
    public static String forSend(int sendQueueId, String domain) {
        if (sendQueueId <= 0 || domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Faltan el envío o el dominio del Message-ID");
        }
        StringBuilder aleatorio = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            aleatorio.append(ALFABETO[AZAR.nextInt(ALFABETO.length)]);
        }
        return "<pia." + sendQueueId + "." + aleatorio + "@" + domain.strip() + ">";
    }

    /** El primer envío nuestro que se mencione en alguno de los textos (encabezados, rebote, cuerpo). */
    public static Optional<Integer> findSendId(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = ID.matcher(text);
            if (m.find()) {
                try {
                    return Optional.of(Integer.valueOf(m.group(1)));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
