package co.com.gtc.prospeccion.prospect.service;

import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;

/**
 * Consigue y verifica el correo de trabajo de una persona (tarea 4.1): Apollo People Enrichment y, si no alcanza,
 * Hunter. GASTA CRÉDITOS: solo se llama para los contactos calificados por encima del umbral (3.5).
 */
public interface EmailDiscoveryService {

    /**
     * Antes de cada llamada que cuesta revisa los topes (4.2) y después anota en el libro lo que se gastó.
     *
     * @param apolloPersonId ID de Apollo de la persona (el de la búsqueda, la_apolloseen.PROVIDER_ID)
     * @param context        campaña, contacto y usuario a los que se cargan los créditos
     * @return el correo aceptado o por qué no lo hay, con los créditos gastados
     * @throws co.com.gtc.prospeccion.credits.CreditLimitExceededException si el siguiente gasto supera un tope;
     *         lo ya gastado queda anotado y la campaña se detiene
     */
    EmailDiscoveryResult discover(String apolloPersonId, CreditContext context);

    /**
     * Solo el tramo de Hunter (Email Finder y, si hace falta, Email Verifier) con nombre, apellido y dominio ya
     * conocidos, con las mismas reglas de aceptación y los mismos topes de créditos. Lo usa la herramienta local
     * "Probar Correo" mientras no hay plan de Apollo con API; la búsqueda (3.2) entra siempre por {@link #discover}.
     */
    EmailDiscoveryResult discoverWithHunter(String firstName, String lastName, String domain, CreditContext context);
}
