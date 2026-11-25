package co.com.gtc.prospeccion.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlSanitizerUtilTest {

    @Test
    void dejaParrafosListasYNegrita() {
        String html = "<p>Buenos días, <strong>Laura</strong>:</p><ul><li>Uno</li><li><em>Dos</em></li></ul>";

        assertThat(HtmlSanitizerUtil.sanitize(html)).isEqualTo(html);
    }

    @Test
    void quitaScriptsEstilosImagenesYAtributos() {
        String html = "<p style=\"color:red\" onclick=\"robar()\">Hola</p><script>alert(1)</script>"
                + "<img src=\"https://espia.example/pixel.gif\"><div class=\"x\">Texto</div>"
                + "<a href=\"javascript:alert(1)\">clic</a>";

        String limpio = HtmlSanitizerUtil.sanitize(html);

        assertThat(limpio).isEqualTo("<p>Hola</p>Textoclic");
        assertThat(limpio).doesNotContain("script", "style", "img", "onclick", "javascript", "class");
    }

    @Test
    void losEnlacesSoloHttpsOMailtoYConNofollow() {
        String limpio = HtmlSanitizerUtil.sanitize(
                "<a href=\"https://gtc.example/servicios\">Servicios</a> <a href=\"http://inseguro.example\">x</a>");

        assertThat(limpio).contains("href=\"https://gtc.example/servicios\"").contains("rel=\"nofollow\"");
        assertThat(limpio).doesNotContain("http://inseguro.example");
    }

    @Test
    void vacioONuloDaCadenaVacia() {
        assertThat(HtmlSanitizerUtil.sanitize(null)).isEmpty();
        assertThat(HtmlSanitizerUtil.sanitize("   ")).isEmpty();
        assertThat(HtmlSanitizerUtil.toPlainText(null)).isEmpty();
    }

    @Test
    void textoPlanoConParrafosVinetasYAcentos() {
        String limpio = HtmlSanitizerUtil.sanitize("""
                <p>Buenos días, Laura:</p>
                <p>En GTC ayudamos a empresas medianas &amp; grandes. Por ejemplo:</p>
                <ul>
                  <li>Integramos el ERP.</li>
                  <li>Automatizamos tareas.</li>
                </ul>
                <p>¿Le parece si conversamos 20 minutos?<br>Escríbanos a <a href="mailto:ventas@gtc.example">ventas@gtc.example</a></p>
                <p>Equipo Comercial GTC</p>
                """);

        assertThat(HtmlSanitizerUtil.toPlainText(limpio)).isEqualTo("""
                Buenos días, Laura:

                En GTC ayudamos a empresas medianas & grandes. Por ejemplo:

                - Integramos el ERP.
                - Automatizamos tareas.

                ¿Le parece si conversamos 20 minutos?
                Escríbanos a ventas@gtc.example (mailto:ventas@gtc.example)

                Equipo Comercial GTC""");
    }

    @Test
    void decodificaEntidadesNumericasYConNombre() {
        assertThat(HtmlSanitizerUtil.decode("a&#64;b &#x40; &lt;p&gt; &quot;x&quot; &desconocida; &#99999999;"))
                .isEqualTo("a@b @ <p> \"x\" &desconocida; &#99999999;");
    }
}
