package co.com.gtc.prospeccion.crm.port;

import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullContactPersonRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;

/**
 * Puerto hacia el CRM para todo lo que Leads no es dueño (tablas pm_*).
 * Hoy se implementa por API (Feign); el resto del módulo no depende de cómo.
 * msa-cotizaciones no expone endpoints para cambiar solo el estado del prospecto
 * ni para agregar un medio suelto: los medios viajan dentro del prospecto o del contacto.
 */
public interface CrmProspectPort {

    /** IDs que crea register-complete-prospect. */
    record CreatedProspect(Integer leafletId, Integer leafletContactId) {
    }

    /** Crea empresa, contacto y medios en una sola llamada. userId = sc_user.ID que queda como CREATEDBY. */
    CreatedProspect createProspect(FullProspectRequestDTO request, Integer userId);

    /**
     * Agrega una persona (con sus medios) a un pm_leaflet existente; request.contact.leafletET = pm_leaflet.ID.
     * userLogin = sc_user.LOGIN (msa-cotizaciones no tiene variante por ID). Devuelve el ID de pm_leafletcontact.
     */
    Integer createContact(FullContactPersonRequestDTO request, String userLogin);

    /** Crea pm_task y la asocia al prospecto de request.leafletId (pm_taskleaflet). Devuelve el ID de pm_task. */
    Long createTask(CreateTaskRequestDTO request, Integer userId);
}
