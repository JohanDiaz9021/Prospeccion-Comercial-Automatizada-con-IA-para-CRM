package co.com.gtc.prospeccion.sending.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import co.com.gtc.prospeccion.common.util.HtmlSanitizerUtil;
import co.com.gtc.prospeccion.config.PropertiesConfig.Legal;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;

/**
 * Arma el correo que sale (tarea 5.2) con la plantilla templates/email/prospect-email.html: el cuerpo aprobado, el
 * LinkedIn de la empresa, el enlace de baja firmado y el pie legal. Nada de esto lo escribe Gemini ni se puede borrar
 * al editar el borrador (docs/negocio/mensaje-base.md). También arma la versión en texto plano.
 */
@Component
public class EmailComposer {

    static final String TEMPLATE = "templates/email/prospect-email.html";
    private static final Pattern LINKEDIN_BLOCK = Pattern.compile("\\s*<!--linkedin-->.*?<!--/linkedin-->", Pattern.DOTALL);
    private static final Pattern MARKERS = Pattern.compile("\\s*<!--/?linkedin-->");
    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[a-z_]+}}");

    private final ProspeccionProperties properties;
    private final UnsubscribeTokenService tokens;
    private final String template;

    public EmailComposer(ProspeccionProperties properties, UnsubscribeTokenService tokens) {
        this.properties = properties;
        this.tokens = tokens;
        this.template = loadTemplate();
    }

    /**
     * Lo que falta para poder enviar. Vacío = se puede. Sin pie legal completo, remitente o enlace de baja no sale
     * ningún correo (Ley 2300 y docs/negocio/mensaje-base.md).
     */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        String from = properties.getMailbox().getFromAddress();
        if (from == null || !from.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            missing.add("el buzón que envía (MAILBOX_FROM_ADDRESS)");
        }
        Legal legal = properties.getLegal();
        if (incomplete(legal.getCompanyName())) {
            missing.add("el nombre de la empresa del pie legal (LEGAL_COMPANY_NAME)");
        }
        if (incomplete(legal.getNit())) {
            missing.add("el NIT del pie legal (LEGAL_NIT, tarea 1.10)");
        }
        if (incomplete(legal.getAddress())) {
            missing.add("la dirección del pie legal (LEGAL_ADDRESS, tarea 1.10)");
        }
        if (incomplete(legal.getPrivacyPolicyUrl()) || !legal.getPrivacyPolicyUrl().strip().startsWith("http")) {
            missing.add("el enlace a la política de datos (LEGAL_PRIVACY_POLICY_URL, tarea 1.10)");
        }
        String unsubscribe = properties.getUnsubscribe().getBaseUrl();
        if (unsubscribe == null || unsubscribe.isBlank()) {
            missing.add("la dirección de la página de baja (UNSUBSCRIBE_BASE_URL)");
        }
        return missing;
    }

    /**
     * Correo listo para el buzón.
     *
     * @param organizationName empresa del contacto (de su evidencia); puede ser null
     * @param messageId        Message-ID de MessageIds.forSend
     */
    public OutgoingEmail compose(SendQueueET send, DraftET draft, TenantConfigET config, String organizationName,
            String messageId) {
        Legal legal = properties.getLegal();
        String company = legal.getCompanyName().strip();
        String unsubscribeUrl = tokens.createUrl(send.getId());
        String linkedin = config == null ? null : httpsOrNull(config.getCompanyLinkedinUrl());
        String body = HtmlSanitizerUtil.sanitize(draft.getBody());
        String empresa = organizationName == null || organizationName.isBlank() ? "su empresa" : organizationName.strip();

        String html = linkedin == null
                ? LINKEDIN_BLOCK.matcher(template).replaceAll("")
                : MARKERS.matcher(template).replaceAll("");
        html = html
                .replace("{{cuerpo}}", body)
                .replace("{{empresa}}", escape(empresa))
                .replace("{{empresa_remitente}}", escape(company))
                .replace("{{linkedin_empresa}}", linkedin == null ? "" : escape(linkedin))
                .replace("{{enlace_baja}}", escape(unsubscribeUrl))
                .replace("{{nit}}", escape(legal.getNit().strip()))
                .replace("{{direccion}}", escape(legal.getAddress().strip()))
                .replace("{{politica_datos}}", escape(legal.getPrivacyPolicyUrl().strip()));
        if (properties.getTracking().getOpenPixel().isEnabled()) {
            html = html.replace("</body>", "  <img src=\"" + escape(tokens.createOpenPixelUrl(send.getId()))
                    + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0;\">\n</body>");
        }
        // El sanitizador puede dejar las llaves como entidades (&#123;): se revisa también el texto ya decodificado.
        String bodyText = HtmlSanitizerUtil.toPlainText(body).strip();
        for (String version : List.of(html, bodyText)) {
            Matcher leftover = LEFTOVER.matcher(version);
            if (leftover.find()) {
                throw new IllegalStateException("El correo quedó con " + leftover.group() + " sin llenar");
            }
        }

        StringBuilder text = new StringBuilder(bodyText).append("\n\n");
        if (linkedin != null) {
            text.append(company).append(" en LinkedIn: ").append(linkedin).append("\n\n");
        }
        text.append("--\n")
                .append("Le escribimos a su correo corporativo porque su cargo en ").append(empresa)
                .append(" se relaciona con los servicios de tecnología de ").append(company)
                .append(". Sus datos de contacto profesional provienen de una base de datos comercial B2B.\n\n")
                .append("Si no desea recibir más mensajes nuestros, puede darse de baja aquí: ").append(unsubscribeUrl)
                .append("\nAl darse de baja no volverá a recibir correos de ").append(company).append(".\n\n")
                .append(company).append(" · NIT ").append(legal.getNit().strip()).append(" · ")
                .append(legal.getAddress().strip()).append(" · Política de tratamiento de datos: ")
                .append(legal.getPrivacyPolicyUrl().strip()).append('\n');

        return OutgoingEmail.builder()
                .mailboxKey(config == null ? null : config.getMailboxSsmKey())
                .fromAddress(properties.getMailbox().getFromAddress().strip())
                .fromName(properties.getMailbox().getFromName())
                .to(send.getRecipientEmail())
                .subject(oneLine(draft.getSubject()))
                .bodyHtml(html)
                .bodyText(text.toString())
                .unsubscribeUrl(unsubscribeUrl)
                .messageId(messageId)
                .threadId(send.getThreadId())
                .build();
    }

    private static String loadTemplate() {
        try (InputStream in = new ClassPathResource(TEMPLATE).getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // El comentario de arriba documenta la plantilla: no va en el correo.
            int doctype = raw.indexOf("<!DOCTYPE");
            return doctype >= 0 ? raw.substring(doctype) : raw;
        } catch (IOException e) {
            throw new IllegalStateException("No se encontró la plantilla del correo " + TEMPLATE, e);
        }
    }

    private static boolean incomplete(String value) {
        // [completar] y similares son marcas del pie propuesto: nunca pueden salir en un correo.
        return value == null || value.isBlank() || value.contains("[") || value.contains("]");
    }

    private static String httpsOrNull(String url) {
        return url != null && url.strip().startsWith("https://") ? url.strip() : null;
    }

    private static String oneLine(String subject) {
        return subject == null ? "" : subject.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }
}
