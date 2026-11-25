package co.com.gtc.prospeccion.crm.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;
import co.com.gtc.prospeccion.crm.client.CotizacionesFeignClient;
import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.dto.CrmIdResponseDTO;
import co.com.gtc.prospeccion.crm.dto.CrmResponseDTO;

class CrmProspectFeignAdapterTest {

    private final CotizacionesFeignClient client = mock(CotizacionesFeignClient.class);
    private final CrmProspectFeignAdapter adapter = new CrmProspectFeignAdapter(client);

    private static CrmResponseDTO<CrmIdResponseDTO> respuesta(boolean ok, Long id) {
        CrmResponseDTO<CrmIdResponseDTO> r = new CrmResponseDTO<>();
        r.setSuccess(ok);
        r.setErrorsMessage(ok ? null : "lblDateNotFound");
        if (id != null) {
            CrmIdResponseDTO data = new CrmIdResponseDTO();
            data.setId(id);
            r.setData(data);
        }
        return r;
    }

    @Test
    void creaLaTareaYDevuelveSuId() {
        CreateTaskRequestDTO tarea = CreateTaskRequestDTO.builder().name("Respondió").leafletId(300).build();
        when(client.createTask(tarea, 15)).thenReturn(respuesta(true, 5001L));

        assertThat(adapter.createTask(tarea, 15)).isEqualTo(5001L);
    }

    @Test
    void siElCrmRechazaONoRespondeEsUnErrorExterno() {
        when(client.createTask(any(), eq(15))).thenReturn(respuesta(false, null));
        assertThatThrownBy(() -> adapter.createTask(new CreateTaskRequestDTO(), 15))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("lblDateNotFound");

        when(client.createTask(any(), eq(16))).thenThrow(new RuntimeException("Connection refused"));
        assertThatThrownBy(() -> adapter.createTask(new CreateTaskRequestDTO(), 16))
                .isInstanceOf(ExternalServiceException.class);
    }
}
