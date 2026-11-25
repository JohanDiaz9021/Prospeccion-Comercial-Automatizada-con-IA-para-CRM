package co.com.gtc.prospeccion.prospect.service;

import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;

/**
 * Crea el prospecto en el CRM (vía CrmProspectPort) y guarda los datos extra en la_*.
 */
public interface ProspectCreationService {

    /**
     * Crea empresa, contacto y correo en el CRM y guarda en la_leafletext, la_contactext y la_evidence lo que trajo
     * Apollo, con su fuente. La evidencia es lo único que la redacción (4.3) puede usar.
     *
     * @param found           resultado FOUND de la 4.1: correo aceptado y la persona completa de Apollo
     * @param qualification   calificación de la 3.5 (puntaje, motivo, modelo y versión del prompt)
     * @param searchRunId     la_searchrun.ID que lo trajo
     * @param pmListContactId lista del CRM a la que se asocia el contacto (opcional)
     * @param userId          sc_user.ID que queda como CREATEDBY
     * @return el ID de pm_leafletcontact creado
     */
    Integer createProspect(EmailDiscoveryResult found, GeminiQualificationResult qualification, Integer searchRunId,
            Integer pmListContactId, Integer userId);
}
