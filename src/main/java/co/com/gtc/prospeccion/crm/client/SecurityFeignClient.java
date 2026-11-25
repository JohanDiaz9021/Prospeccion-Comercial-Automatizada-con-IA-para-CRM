package co.com.gtc.prospeccion.crm.client;

import org.springframework.cloud.openfeign.FeignClient;

/**
 * msa-security-crm: usuarios y providers.
 */
@FeignClient(name = "msa-security-crm")
public interface SecurityFeignClient {

    // TODO: declarar los endpoints de usuarios y providers cuando el equipo CRM confirme rutas y DTO.
}
