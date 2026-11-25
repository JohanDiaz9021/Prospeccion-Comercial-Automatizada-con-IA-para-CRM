package co.com.gtc.prospeccion.crm.client;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * msa-params-crm: labels (claves lbl*) y masterTables.
 */
@FeignClient(name = "msa-params-crm")
public interface ParamsFeignClient {

    // TODO: declarar los endpoints de labels y masterTables cuando el equipo CRM confirme rutas y DTO.
}
