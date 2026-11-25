package co.com.gtc.prospeccion.crm.adapter;

import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;
import co.com.gtc.prospeccion.crm.client.CotizacionesFeignClient;
import co.com.gtc.prospeccion.crm.dto.CrmIdResponseDTO;
import co.com.gtc.prospeccion.crm.dto.CrmResponseDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletCompleteResponseDTO;
import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullContactPersonRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;

/**
 * Implementación de CrmProspectPort contra msa-cotizaciones.
 * Si la respuesta trae success=false, data vacía o Feign falla, se lanza ExternalServiceException("msa-cotizaciones").
 */
@Component
public class CrmProspectFeignAdapter implements CrmProspectPort {

    static final String PROVIDER = "msa-cotizaciones";

    private final CotizacionesFeignClient cotizacionesClient;

    public CrmProspectFeignAdapter(CotizacionesFeignClient cotizacionesClient) {
        this.cotizacionesClient = cotizacionesClient;
    }

    @Override
    public CreatedProspect createProspect(FullProspectRequestDTO request, Integer userId) {
        CrmResponseDTO<LeafletCompleteResponseDTO> response;
        try {
            response = cotizacionesClient.registerCompleteProspect(request, userId);
        } catch (RuntimeException e) {
            throw new ExternalServiceException(PROVIDER, "No se pudo crear el prospecto en el CRM", e);
        }
        LeafletCompleteResponseDTO data = response == null ? null : response.getData();
        if (response == null || !response.isSuccess() || data == null || data.getId() == null || data.getContactId() == null) {
            throw new ExternalServiceException(PROVIDER, "El CRM no devolvió el prospecto creado: "
                    + (response == null ? "sin respuesta" : response.getErrorsMessage()), null);
        }
        return new CreatedProspect(data.getId(), data.getContactId());
    }

    @Override
    public Integer createContact(FullContactPersonRequestDTO request, String userLogin) {
        // TODO: cotizacionesClient.createPersonContact(request, userLogin) -> data.id.
        throw new UnsupportedOperationException("TODO");
    }

    /** 5.6: task/create-by-user-id crea pm_task y, con leafletId, pm_taskleaflet (así sale en el historial). */
    @Override
    public Long createTask(CreateTaskRequestDTO request, Integer userId) {
        CrmResponseDTO<CrmIdResponseDTO> response;
        try {
            response = cotizacionesClient.createTask(request, userId);
        } catch (RuntimeException e) {
            throw new ExternalServiceException(PROVIDER, "No se pudo crear la tarea en el CRM", e);
        }
        CrmIdResponseDTO data = response == null ? null : response.getData();
        if (response == null || !response.isSuccess() || data == null || data.getId() == null) {
            throw new ExternalServiceException(PROVIDER, "El CRM no devolvió la tarea creada: "
                    + (response == null ? "sin respuesta" : response.getErrorsMessage()), null);
        }
        return data.getId();
    }
}
