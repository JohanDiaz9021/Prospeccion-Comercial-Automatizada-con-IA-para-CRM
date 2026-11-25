package co.com.gtc.prospeccion.tracking.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Classification;
import co.com.gtc.prospeccion.tracking.inbound.InboundClassifier.Kind;

class InboundClassifierTest {

    private static IncomingMessage.IncomingMessageBuilder respuesta(String cuerpo) {
        return IncomingMessage.builder().from("Ana Pérez <ana@clinica.example>").subject("RE: Clínica: sistemas conectados")
                .headers(Map.of("content-type", "text/plain")).bodyText(cuerpo);
    }

    @Test
    void unaPersonaQueContestaEsRespuesta() {
        assertThat(InboundClassifier.classify(respuesta("Me interesa, llámeme el jueves.").build()).kind())
                .isEqualTo(Kind.REPLY);
    }

    @Test
    void pedirLaBajaEscribiendoEsBajaAunqueTengaTildesOMayusculas() {
        assertThat(InboundClassifier.classify(respuesta("Por favor DARME DE BAJA.").build()).kind())
                .isEqualTo(Kind.UNSUBSCRIBE_REQUEST);
        assertThat(InboundClassifier.classify(respuesta("No deseo recibir más correos, gracias").build()).kind())
                .isEqualTo(Kind.UNSUBSCRIBE_REQUEST);
        assertThat(InboundClassifier.classify(respuesta("Please remove me from your list").build()).kind())
                .isEqualTo(Kind.UNSUBSCRIBE_REQUEST);
        assertThat(InboundClassifier.classify(respuesta("").subject("RE: Baja").build()).kind())
                .isEqualTo(Kind.UNSUBSCRIBE_REQUEST);
    }

    @Test
    void laBajaQueEstaEnNuestroCorreoCitadoNoCuenta() {
        String gmail = "Gracias, lo reviso con mi equipo.\n\nEl lun, 24 nov 2026 a las 9:00, Comercial GTC <c@gtc.example> "
                + "escribió:\n> Si no desea recibir más correos, puede darme de baja aquí.";
        String outlook = "Gracias, lo reviso.\n\n________________________________\nDe: Comercial GTC <c@gtc.example>\n"
                + "Enviado: lunes\nSi no desea recibir más correos, puede darse de baja.";
        assertThat(InboundClassifier.classify(respuesta(gmail).build()).kind()).isEqualTo(Kind.REPLY);
        assertThat(InboundClassifier.classify(respuesta(outlook).build()).kind()).isEqualTo(Kind.REPLY);
    }

    @Test
    void fueraDeLaOficinaNoEsRespuesta() {
        assertThat(InboundClassifier.classify(respuesta("Estoy de vacaciones").headers(Map.of("auto-submitted", "auto-replied"))
                .build()).kind()).isEqualTo(Kind.AUTO_REPLY);
        assertThat(InboundClassifier.classify(respuesta("Regreso el lunes").subject("Respuesta automática: Clínica")
                .build()).kind()).isEqualTo(Kind.AUTO_REPLY);
        assertThat(InboundClassifier.classify(respuesta("Back on Monday").subject("Automatic reply: hello")
                .build()).kind()).isEqualTo(Kind.AUTO_REPLY);
        assertThat(InboundClassifier.classify(respuesta("hola").headers(Map.of("x-autoreply", "yes")).build()).kind())
                .isEqualTo(Kind.AUTO_REPLY);
        // Auto-Submitted: no es un correo normal.
        assertThat(InboundClassifier.classify(respuesta("hola").headers(Map.of("auto-submitted", "no")).build()).kind())
                .isEqualTo(Kind.REPLY);
    }

    @Test
    void reboteConCodigo5EsDuroYCon4EsTemporal() {
        IncomingMessage duro = IncomingMessage.builder().from("mailer-daemon@googlemail.com")
                .headers(Map.of("content-type", "multipart/report; report-type=delivery-status"))
                .deliveryStatus("Final-Recipient: rfc822; Ana@Clinica.example\nAction: failed\nStatus: 5.1.1").build();
        Classification c = InboundClassifier.classify(duro);
        assertThat(c.kind()).isEqualTo(Kind.HARD_BOUNCE);
        assertThat(c.bouncedRecipient()).isEqualTo("ana@clinica.example");
        assertThat(c.status()).isEqualTo("5.1.1");

        IncomingMessage temporal = IncomingMessage.builder().deliveryStatus(
                "Final-Recipient: rfc822;ana@clinica.example\nAction: failed\nStatus: 4.2.2").build();
        assertThat(InboundClassifier.classify(temporal).kind()).isEqualTo(Kind.SOFT_BOUNCE);
        IncomingMessage demorado = IncomingMessage.builder().deliveryStatus(
                "Final-Recipient: rfc822;ana@clinica.example\nAction: delayed\nStatus: 5.0.0").build();
        assertThat(InboundClassifier.classify(demorado).kind()).isEqualTo(Kind.SOFT_BOUNCE);
    }

    @Test
    void reboteDeOutlookSinPartesSeReconocePorRemitenteAsuntoYTexto() {
        IncomingMessage outlook = IncomingMessage.builder().from("Microsoft Outlook <postmaster@empresa.onmicrosoft.com>")
                .subject("Undeliverable: Clínica: sistemas conectados")
                .bodyText("Your message to ana@clinica.example couldn't be delivered. The email address doesn't exist.")
                .build();
        assertThat(InboundClassifier.classify(outlook).kind()).isEqualTo(Kind.HARD_BOUNCE);

        IncomingMessage lleno = IncomingMessage.builder().from("MAILER-DAEMON@mx.example")
                .subject("Returned mail: see transcript").bodyText("Mailbox quota exceeded, try again later").build();
        assertThat(InboundClassifier.classify(lleno).kind()).isEqualTo(Kind.SOFT_BOUNCE);
    }

    @Test
    void unaQuejaEsQueja() {
        IncomingMessage queja = IncomingMessage.builder().from("feedback@proveedor.example")
                .headers(Map.of("content-type", "multipart/report; report-type=feedback-report"))
                .feedbackReport("Feedback-Type: abuse\nOriginal-Rcpt-To: ana@clinica.example").build();
        assertThat(InboundClassifier.classify(queja).kind()).isEqualTo(Kind.COMPLAINT);
    }

    @Test
    void sacaLaDireccionDelRemitente() {
        assertThat(InboundClassifier.address("Ana Pérez <Ana@Clinica.example>")).isEqualTo("ana@clinica.example");
        assertThat(InboundClassifier.address("ana@clinica.example")).isEqualTo("ana@clinica.example");
        assertThat(InboundClassifier.address(null)).isNull();
    }

    @Test
    void elTextoNuevoTerminaDondeEmpiezaLaCita() {
        assertThat(InboundClassifier.newText("Hola\n> citado")).isEqualTo("Hola\n");
        assertThat(InboundClassifier.newText("Hola\nOn Mon, Nov 24, 2026 at 9:00 AM GTC wrote:\n> x")).startsWith("Hola")
                .doesNotContain("wrote");
        assertThat(InboundClassifier.newText(null)).isEmpty();
    }
}
