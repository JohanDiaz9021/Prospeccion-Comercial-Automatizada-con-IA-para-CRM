package co.com.gtc.prospeccion.prospect.dto;

import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de conseguir el correo de un contacto (tarea 4.1). Se guarda en la_contactext (EMAIL_SOURCE y
 * EMAIL_VERIFICATION) y los créditos van al registro de créditos (tarea 4.2).
 * No registrar en logs: contiene el correo y los datos de la persona.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailDiscoveryResult {

    public enum Outcome {
        /** Hay un correo aceptado: se puede redactar (4.3). */
        FOUND,
        /** Apollo no reconoce la persona. */
        NO_MATCH,
        /** Ni Apollo ni Hunter tienen el correo (o faltan dominio o apellido para buscarlo). */
        NOT_FOUND,
        /** Hay correo pero no sirve: inválido, personal, desechable o sin verificar. Ver {@link #verification}. */
        DISCARDED,
        /** Hunter respondió 451: la persona pidió no ser procesada. Va a la lista de supresión (5.5). */
        OPTED_OUT
    }

    public static final String SOURCE_APOLLO = "APOLLO";
    public static final String SOURCE_HUNTER = "HUNTER";
    /** Apollo lo marcó como verificado y no se gastó una verificación de Hunter. */
    public static final String APOLLO_VERIFIED = "APOLLO_VERIFIED";
    /** El dominio del correo está en exclusions.freeEmailDomains del perfil (gmail, hotmail...). */
    public static final String PERSONAL_DOMAIN = "PERSONAL_DOMAIN";

    private Outcome outcome;
    /** Correo aceptado (solo con FOUND) o descartado (con DISCARDED). */
    private String email;
    /** APOLLO o HUNTER (la_contactext.EMAIL_SOURCE). */
    private String source;
    /** APOLLO_VERIFIED, el estado de Hunter (valid, accept_all, invalid...) o PERSONAL_DOMAIN. */
    private String verification;
    /** Puntaje de Hunter (0 a 100), si lo hubo. */
    private Integer score;
    /** Datos que devolvió Apollo (apellido completo, empresa y dominio) para crear el prospecto. */
    private ApolloPersonDTO person;

    /** Créditos gastados, para la tarea 4.2. */
    private int apolloCredits;
    private double hunterSearchCredits;
    private double hunterVerificationCredits;

    public boolean isFound() {
        return outcome == Outcome.FOUND;
    }

    /** No imprime el correo ni la persona. */
    @Override
    public String toString() {
        return "EmailDiscoveryResult[outcome=" + outcome + ", source=" + source + ", verification=" + verification
                + ", apolloCredits=" + apolloCredits + ", hunterSearchCredits=" + hunterSearchCredits
                + ", hunterVerificationCredits=" + hunterVerificationCredits + "]";
    }
}
