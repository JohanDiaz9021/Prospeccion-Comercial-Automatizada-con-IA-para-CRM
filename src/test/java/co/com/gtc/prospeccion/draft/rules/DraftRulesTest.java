package co.com.gtc.prospeccion.draft.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.draft.rules.DraftRules.Check;
import co.com.gtc.prospeccion.draft.rules.DraftRules.Issue;

/** Reglas del borrador (docs/negocio/mensaje-base.md) con el mensaje base aprobado y su ejemplo. */
class DraftRulesTest {

    static final String DATOS = "{\"firstName\":\"Laura\",\"title\":\"Directora de Tecnología\","
            + "\"organizationName\":\"Clínica Los Andes\",\"industry\":\"hospital & health care\",\"companySize\":\"250\"}";

    /** El ejemplo de borrador del documento (contacto ficticio). */
    static final String EJEMPLO = """
            Buenos días, Laura:

            En una clínica del tamaño de Clínica Los Andes, la dirección de tecnología suele tener que mantener al día varios sistemas (historia clínica, facturación, inventarios) que no siempre comparten la información.

            En GTC ayudamos a empresas medianas colombianas a conectar sus sistemas y automatizar procesos manuales para que operen más rápido y con menos errores. Por ejemplo:

            - Integramos el ERP, el CRM y la facturación para que la información esté en un solo lugar.
            - Automatizamos tareas repetitivas con software a la medida e inteligencia artificial.
            - Damos soporte y mantenimiento a plataformas críticas cuando el equipo interno no da abasto.

            ¿Le parece si conversamos 20 minutos la próxima semana para ver si algo de esto le sirve a Clínica Los Andes?

            Cordialmente,

            Equipo Comercial GTC""";

    static String mensajeBase() throws IOException {
        return Files.readString(Path.of("docs/negocio/mensaje-base.txt"), StandardCharsets.UTF_8);
    }

    @Test
    void elEjemploDelDocumentoCumpleSinAvisos() throws IOException {
        Check check = DraftRules.check("Clínica Los Andes: sistemas conectados", EJEMPLO, mensajeBase(), DATOS);

        assertThat(check.errors()).isEmpty();
        assertThat(check.warnings()).isEmpty();
        assertThat(check.words()).isBetween(DraftRules.MIN_WORDS, DraftRules.MAX_WORDS);
    }

    @Test
    void elAsuntoDelMensajeBaseConUnaEmpresaDe17LetrasPasaDe60() throws IOException {
        // El asunto aprobado deja 14 caracteres para la empresa: con nombres más largos, quien revisa ve el aviso.
        String asunto = "Clínica Los Andes: menos procesos manuales, sistemas conectados";

        assertThat(asunto).hasSize(63);
        assertThat(DraftRules.check(asunto, EJEMPLO, mensajeBase(), DATOS).warnings())
                .containsExactly(Issue.SUBJECT_TOO_LONG);
    }

    @Test
    void marcasSinLlenarYSinFirmaSonErrores() throws IOException {
        String cuerpo = EJEMPLO.replace("Buenos días, Laura:", "Buenos días, {{nombre}}:")
                .replace("Equipo Comercial GTC", "Juan Pérez");

        Check check = DraftRules.check("Asunto", cuerpo, mensajeBase(), DATOS);

        assertThat(check.isValid()).isFalse();
        assertThat(check.errors()).containsExactly(Issue.MARKS_LEFT, Issue.SIGNATURE_MISSING);
    }

    @Test
    void unaInstruccionPersonalizarQueQuedoEsError() throws IOException {
        Check check = DraftRules.check("Asunto", EJEMPLO + "\n[[PERSONALIZAR: algo]]", mensajeBase(), DATOS);

        assertThat(check.errors()).containsExactly(Issue.MARKS_LEFT);
    }

    @Test
    void laFirmaSeReconoceAunqueCambieLaPuntuacion() throws IOException {
        Check check = DraftRules.check("Asunto", EJEMPLO.replace("Equipo Comercial GTC", "Equipo comercial GTC."),
                mensajeBase(), DATOS);

        assertThat(check.errors()).isEmpty();
    }

    @Test
    void vacioEsError() throws IOException {
        Check check = DraftRules.check(" ", "", mensajeBase(), DATOS);

        assertThat(check.errors()).containsExactly(Issue.EMPTY_SUBJECT, Issue.EMPTY_BODY);
    }

    @Test
    void numerosQueNoVienenEnLosDatosSonAviso() throws IOException {
        String cuerpo = EJEMPLO.replace("varios sistemas", "más de 15 sistemas")
                .replace("con menos errores", "con un 40 % menos de errores");

        Check check = DraftRules.check("Asunto", cuerpo, mensajeBase(), DATOS);

        assertThat(check.isValid()).isTrue();
        assertThat(check.warnings()).containsExactly(Issue.NUMBER_NOT_IN_DATA);
    }

    @Test
    void losNumerosDelContactoYDelMensajeBaseSePermiten() throws IOException {
        String cuerpo = EJEMPLO.replace("En una clínica del tamaño", "En una clínica de 250 empleados como");

        assertThat(DraftRules.check("Asunto", cuerpo, mensajeBase(), DATOS).warnings()).isEmpty();
    }

    @Test
    void largoAsuntoExclamacionesEmojiYMayusculasSonAvisos() throws IOException {
        String asunto = "Clínica Los Andes: una propuesta para conectar todos sus sistemas hoy";
        String cuerpo = EJEMPLO.replace("Buenos días, Laura:", "¡¡Buenos días, Laura!! 🚀 MUY IMPORTANTE:");

        Check check = DraftRules.check(asunto, cuerpo, mensajeBase(), DATOS);

        assertThat(check.isValid()).isTrue();
        assertThat(check.warnings())
                .containsExactly(Issue.SUBJECT_TOO_LONG, Issue.EXCLAMATIONS, Issue.EMOJI, Issue.SHOUTING);
    }

    @Test
    void siglasCortasNoSonMayusculasSostenidas() throws IOException {
        // "ERP", "CRM" y "GTC" son siglas: no cuentan como gritar.
        assertThat(DraftRules.check("Asunto", EJEMPLO, mensajeBase(), DATOS).warnings()).doesNotContain(Issue.SHOUTING);
    }

    @Test
    void muyCortoOMuyLargoSonAvisos() throws IOException {
        String corto = "Buenos días, Laura:\n\n¿Conversamos?\n\nEquipo Comercial GTC";
        String largo = EJEMPLO.replace("Por ejemplo:", "Por ejemplo: " + "palabra ".repeat(60));

        assertThat(DraftRules.check("Asunto", corto, mensajeBase(), DATOS).warnings()).containsExactly(Issue.TOO_SHORT);
        assertThat(DraftRules.check("Asunto", largo, mensajeBase(), DATOS).warnings()).containsExactly(Issue.TOO_LONG);
    }

    @Test
    void unAsuntoDeMasDe150CaracteresEsError() throws IOException {
        assertThat(DraftRules.check("a".repeat(151), EJEMPLO, mensajeBase(), DATOS).errors())
                .containsExactly(Issue.SUBJECT_UNUSABLE);
    }

    @Test
    void limpiaElAsunto() {
        assertThat(DraftRules.cleanSubject("ASUNTO: RE: FW:  Clínica   Los Andes\nsegunda línea"))
                .isEqualTo("Clínica Los Andes");
        assertThat(DraftRules.cleanSubject(null)).isEmpty();
    }

    @Test
    void laFirmaEsLaUltimaLineaDelMensajeBase() throws IOException {
        assertThat(DraftRules.signatureOf(mensajeBase())).isEqualTo("Equipo Comercial GTC");
        assertThat(DraftRules.signatureOf("ASUNTO: x\n\nHola {{nombre}}")).isNull();
        assertThat(DraftRules.signatureOf(null)).isNull();
    }

    @Test
    void cuentaPalabrasSinVinetas() {
        assertThat(DraftRules.countWords("- Uno dos\n- tres, 20 minutos")).isEqualTo(5);
        assertThat(DraftRules.countWords("  ")).isZero();
    }
}
