package co.com.gtc.prospeccion.sending.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.integration.mailbox.dto.OutgoingEmail;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;

class EmailComposerTest {

    static final String BAJA = "http://localhost:8099/msa-prospeccion/public/unsubscribe?t=abc.def";

    private final ProspeccionProperties properties = new ProspeccionProperties();
    private final UnsubscribeTokenService tokens = mock(UnsubscribeTokenService.class);
    private EmailComposer composer;
    private SendQueueET send;
    private DraftET draft;
    private TenantConfigET config;

    @BeforeEach
    void preparar() {
        properties.getMailbox().setFromAddress("prospeccion@gtc-prueba.local");
        properties.getLegal().setNit("900.000.000-1");
        properties.getLegal().setAddress("Calle 1 # 2-3, Bogotá");
        properties.getLegal().setPrivacyPolicyUrl("https://gtc.example/politica");
        properties.getUnsubscribe().setBaseUrl("http://localhost:8099/msa-prospeccion/public/unsubscribe");
        when(tokens.createUrl(42)).thenReturn(BAJA);
        when(tokens.createOpenPixelUrl(42)).thenReturn("https://crm.example/open/tok.gif");
        composer = new EmailComposer(properties, tokens);

        send = new SendQueueET();
        ReflectionTestUtils.setField(send, "id", 42);
        send.setRecipientEmail("ana.perez@clinica-demo.example");
        draft = new DraftET();
        draft.setSubject("Clínica Demo:\r\n menos procesos manuales");
        draft.setBody("<p>Buenos días, Ana:</p><p>Le escribo por <strong>integración</strong>.</p>");
        config = new TenantConfigET();
        config.setCompanyLinkedinUrl("https://www.linkedin.com/company/gtc");
        config.setMailboxSsmKey("/config/gtc/mailbox");
    }

    @Test
    void armaElCorreoConElCuerpoElPieLegalYLaBaja() {
        OutgoingEmail email = composer.compose(send, draft, config, "Clínica Demo", "<pia.42.x@gtc-prueba.local>");

        assertThat(email.getTo()).isEqualTo("ana.perez@clinica-demo.example");
        assertThat(email.getFromAddress()).isEqualTo("prospeccion@gtc-prueba.local");
        assertThat(email.getFromName()).isEqualTo("Equipo Comercial GTC");
        assertThat(email.getSubject()).isEqualTo("Clínica Demo: menos procesos manuales");
        assertThat(email.getMessageId()).isEqualTo("<pia.42.x@gtc-prueba.local>");
        assertThat(email.getUnsubscribeUrl()).isEqualTo(BAJA);
        assertThat(email.getMailboxKey()).isEqualTo("/config/gtc/mailbox");
        assertThat(email.getBodyHtml())
                .startsWith("<!DOCTYPE html>")
                .doesNotContain("Plantilla del correo") // el comentario de la plantilla no sale
                .contains("<strong>integración</strong>")
                .contains("su cargo en Clínica Demo")
                .contains("href=\"https://www.linkedin.com/company/gtc\"")
                .contains("GTC Corporation en LinkedIn")
                .contains("href=\"" + BAJA + "\"")
                .contains("NIT 900.000.000-1").contains("Calle 1 # 2-3, Bogotá")
                .contains("href=\"https://gtc.example/politica\"")
                .doesNotContain("{{").doesNotContain("linkedin-->").doesNotContain("<img");
        assertThat(email.getBodyText())
                .startsWith("Buenos días, Ana:")
                .contains("GTC Corporation en LinkedIn: https://www.linkedin.com/company/gtc")
                .contains("darse de baja aquí: " + BAJA)
                .contains("NIT 900.000.000-1");
    }

    @Test
    void laEmpresaDelContactoSeEscapaYSinLinkedinSeQuitaEseBloque() {
        config.setCompanyLinkedinUrl(null);

        OutgoingEmail email = composer.compose(send, draft, config, "<script>x</script> & Cía", "<pia.42.x@d>");

        assertThat(email.getBodyHtml())
                .contains("&lt;script&gt;x&lt;/script&gt; &amp; Cía")
                .doesNotContain("<script>")
                .doesNotContain("LinkedIn");
        assertThat(email.getBodyText()).doesNotContain("LinkedIn");
    }

    @Test
    void sinEmpresaDiceSuEmpresaYUnLinkedinSinHttpsNoSale() {
        config.setCompanyLinkedinUrl("http://linkedin.com/inseguro");

        OutgoingEmail email = composer.compose(send, draft, config, null, "<pia.42.x@d>");

        assertThat(email.getBodyHtml()).contains("su cargo en su empresa").doesNotContain("inseguro");
    }

    @Test
    void conElPixelEncendidoLoAgregaAlFinal() {
        properties.getTracking().getOpenPixel().setEnabled(true);

        OutgoingEmail email = composer.compose(send, draft, config, "Clínica Demo", "<pia.42.x@d>");

        assertThat(email.getBodyHtml()).contains("<img src=\"https://crm.example/open/tok.gif\" width=\"1\"");
    }

    @Test
    void unBorradorConMarcasSinLlenarNoSeEnvia() {
        draft.setBody("<p>Hola {{nombre}}</p>");

        assertThatThrownBy(() -> composer.compose(send, draft, config, "Clínica Demo", "<pia.42.x@d>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{nombre}}");
    }

    @Test
    void conTodoCompletoNoFaltaNada() {
        assertThat(composer.missingSettings()).isEmpty();
    }

    @Test
    void sinPieLegalCompletoORemitenteNoSePuedeEnviar() {
        properties.getLegal().setNit("[completar]");
        properties.getLegal().setAddress(" ");
        properties.getLegal().setPrivacyPolicyUrl("[enlace a la política]");
        properties.getMailbox().setFromAddress("sin-arroba");
        properties.getUnsubscribe().setBaseUrl(null);

        assertThat(composer.missingSettings()).hasSize(5)
                .anyMatch(m -> m.contains("LEGAL_NIT"))
                .anyMatch(m -> m.contains("LEGAL_ADDRESS"))
                .anyMatch(m -> m.contains("LEGAL_PRIVACY_POLICY_URL"))
                .anyMatch(m -> m.contains("MAILBOX_FROM_ADDRESS"))
                .anyMatch(m -> m.contains("UNSUBSCRIBE_BASE_URL"));
    }
}
