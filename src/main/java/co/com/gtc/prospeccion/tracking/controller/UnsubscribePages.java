package co.com.gtc.prospeccion.tracking.controller;

import org.springframework.web.util.HtmlUtils;

/**
 * Páginas públicas de la baja (5.5). HTML autocontenido (sin scripts ni recursos externos) con los colores del
 * estándar de interfaces (Primary #25007A). Los textos son fijos: la página pública no consulta msa-params.
 */
final class UnsubscribePages {

    static final String EMPRESA = "GTC Corporation";

    private UnsubscribePages() {
    }

    /** Antes de confirmar: un solo botón. El GET nunca da de baja (los antivirus abren los enlaces solos). */
    static String confirm(String maskedEmail, String token) {
        return page("¿Dejar de Recibir Correos?", "info",
                "<p>Dejaremos de escribirle a <strong>" + esc(maskedEmail) + "</strong>. Es gratis, es permanente y no "
                        + "tiene que hacer nada más.</p>"
                        + "<form method=\"post\" action=\"?t=" + esc(token) + "\">"
                        + "<button type=\"submit\">Darme de Baja</button></form>");
    }

    static String done(String maskedEmail) {
        return page("Listo, se Dio de Baja", "ok",
                "<p>No volverá a recibir correos de " + EMPRESA + " en <strong>" + esc(maskedEmail) + "</strong>.</p>"
                        + "<p class=\"suave\">Si recibe otro en los próximos minutos, es porque ya estaba en camino.</p>");
    }

    static String already(String maskedEmail) {
        return page("Ya se Había Dado de Baja", "ok",
                "<p><strong>" + esc(maskedEmail) + "</strong> ya no recibe correos de " + EMPRESA
                        + ". No tiene que hacer nada más.</p>");
    }

    static String invalid() {
        return page("Este Enlace no es Válido", "error",
                "<p>El enlace está incompleto o fue modificado. Ábralo de nuevo desde el correo que recibió, sin "
                        + "cambiarlo.</p>");
    }

    static String unavailable() {
        return page("No Pudimos Procesar su Solicitud", "error",
                "<p>Intente de nuevo en unos minutos. Su solicitud no se perdió si vuelve a abrir el enlace.</p>");
    }

    private static String page(String title, String kind, String body) {
        String icon = switch (kind) {
            case "ok" -> "&#10003;";
            case "error" -> "!";
            default -> "?";
        };
        return """
                <!doctype html>
                <html lang="es">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s · %s</title>
                <style>
                  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #F6F7FB;
                         font: 15px/1.5 Inter, Arial, Helvetica, sans-serif; color: #20202A; }
                  main { width: min(440px, calc(100vw - 32px)); padding: 32px; background: #FFFFFF; border-radius: 20px;
                         box-shadow: 0 10px 28px rgba(32, 32, 42, .18); }
                  .marca { height: 6px; margin: -32px -32px 24px; border-radius: 20px 20px 0 0;
                           background: linear-gradient(90deg, #6EF2AA, #39D4CC, #1D288D, #25007A); }
                  .icono { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%%;
                           font-weight: 700; color: #FFFFFF; background: %s; }
                  h1 { margin: 16px 0 8px; font: 700 22px/30px Poppins, Arial, sans-serif; }
                  p { margin: 0 0 12px; }
                  .suave { color: #6B6B76; font-size: 13px; }
                  button { margin-top: 8px; height: 40px; padding: 0 20px; border: 0; border-radius: 14px;
                           background: #25007A; color: #FFFFFF; font: 600 15px Inter, Arial, sans-serif; cursor: pointer; }
                  button:hover { background: #1D288D; }
                  button:focus-visible { outline: 3px solid #39D4CC; outline-offset: 2px; }
                  footer { margin-top: 24px; color: #6B6B76; font-size: 12px; }
                </style>
                </head>
                <body>
                <main>
                  <div class="marca"></div>
                  <div class="icono" aria-hidden="true">%s</div>
                  <h1>%s</h1>
                  %s
                  <footer>%s</footer>
                </main>
                </body>
                </html>
                """.formatted(esc(title), EMPRESA, color(kind), icon, esc(title), body, EMPRESA);
    }

    private static String color(String kind) {
        return switch (kind) {
            case "ok" -> "#30823A";
            case "error" -> "#B93B31";
            default -> "#25007A";
        };
    }

    private static String esc(String value) {
        // Con la codificación, solo escapa los caracteres peligrosos (< > " ' &) y deja las tildes como texto.
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
