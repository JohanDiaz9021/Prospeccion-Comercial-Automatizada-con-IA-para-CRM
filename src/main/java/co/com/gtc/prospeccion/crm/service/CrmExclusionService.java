package co.com.gtc.prospeccion.crm.service;

import java.util.Optional;

import co.com.gtc.prospeccion.search.rules.ProfileExclusions;

/**
 * Decide si a una persona con correo ya conseguido no se le debe escribir (tarea 3.2, docs/negocio/perfil-de-interes.md
 * sección 8). Se revisa después de conseguir el correo, porque la búsqueda de Apollo no trae correo ni dominio.
 */
public interface CrmExclusionService {

    enum Reason {
        /** El correo está en la lista de bloqueo (baja, rebote duro o queja). */
        SUPPRESSED,
        /** El correo es de la propia empresa (exclusions.ownDomains). */
        COLLABORATOR,
        /** La empresa es un competidor o fue excluida a mano. */
        EXCLUDED_COMPANY,
        /** La prospección ya creó un contacto con ese correo. */
        EXISTING_CONTACT,
        /** El correo ya está en el CRM: contacto de un prospecto creado a mano o un proveedor (cliente, aliado...). */
        CRM_CONTACT,
        /** La prospección ya tiene esa empresa: se escribe a una sola persona por empresa. */
        EXISTING_COMPANY,
        /** Alguien de esa empresa (mismo dominio de correo) ya está en el CRM como prospecto o proveedor. */
        CRM_COMPANY
    }

    /**
     * @param email         correo aceptado en la 4.1
     * @param companyDomain dominio de la empresa según Apollo (puede ser null: se usa el del correo)
     * @param lists         listas del perfil de interés vigente
     * @return por qué no se prospecta, o vacío si se puede
     */
    Optional<Reason> exclusionReason(String email, String companyDomain, ProfileExclusions lists);
}
