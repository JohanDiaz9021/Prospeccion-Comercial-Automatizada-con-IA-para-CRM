package co.com.gtc.prospeccion.credits;

import java.math.BigDecimal;

/**
 * Un gasto de créditos para el libro (la_creditledger).
 *
 * @param provider           APOLLO, HUNTER o GEMINI ({@link CreditCharge#APOLLO}...)
 * @param operation          qué se pagó (PEOPLE_ENRICHMENT, EMAIL_FINDER, EMAIL_VERIFIER...)
 * @param credits            créditos gastados (Hunter cobra 0,5 por verificación)
 * @param searchRunId        campaña (la_searchrun.ID) a la que se carga; null si no es de una búsqueda
 * @param pmLeafletContactId contacto del CRM, si ya existe
 * @param userId             sc_user.ID que queda como CREATEDBY; null = el usuario de la petición actual
 */
public record CreditCharge(String provider, String operation, BigDecimal credits, Integer searchRunId,
        Integer pmLeafletContactId, Integer userId) {

    public static final String APOLLO = "APOLLO";
    public static final String HUNTER = "HUNTER";
    public static final String GEMINI = "GEMINI";

    public static final String PEOPLE_ENRICHMENT = "PEOPLE_ENRICHMENT";
    public static final String EMAIL_FINDER = "EMAIL_FINDER";
    public static final String EMAIL_VERIFIER = "EMAIL_VERIFIER";
}
