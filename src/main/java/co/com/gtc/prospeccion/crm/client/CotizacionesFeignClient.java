package co.com.gtc.prospeccion.crm.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.dto.CrmIdResponseDTO;
import co.com.gtc.prospeccion.crm.dto.CrmResponseDTO;
import co.com.gtc.prospeccion.crm.dto.FullContactPersonRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletCompleteResponseDTO;

/**
 * msa-cotizaciones es el dueño de pm_leaflet, pm_leafletcontact, pm_leafletcontactmedium, pm_task y pm_taskleaflet.
 * Rutas tomadas de sus controllers (context-path /msa-cotizaciones). Solo exige el header
 * X-Tenant-Database-Code: no valida el JWT. Detalle: docs/crm/peticion-completa-y-dominio-de-prospectos.md
 */
@FeignClient(name = "msa-cotizaciones", path = "/msa-cotizaciones/api")
public interface CotizacionesFeignClient {

    /** Crea empresa, contacto y medios. data.id = pm_leaflet.ID, data.contactId = pm_leafletcontact.ID. */
    @PostMapping("/leaflet/register-complete-prospect-by-user-id")
    CrmResponseDTO<LeafletCompleteResponseDTO> registerCompleteProspect(@RequestBody FullProspectRequestDTO request,
            @RequestParam("userId") Integer userId);

    /** Agrega una persona a un pm_leaflet existente. Solo existe la variante por login (userName). */
    @PostMapping("/leafletContact/personContact")
    CrmResponseDTO<CrmIdResponseDTO> createPersonContact(@RequestBody FullContactPersonRequestDTO request,
            @RequestParam("userName") String userLogin);

    /** Crea pm_task y, si viene leafletId, también pm_taskleaflet. Responde 201. */
    @PostMapping("/task/create-by-user-id")
    CrmResponseDTO<CrmIdResponseDTO> createTask(@RequestBody CreateTaskRequestDTO request,
            @RequestParam("userId") Integer userId);
}
