package co.com.gtc.prospeccion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import co.com.gtc.prospeccion.multitenancy.TenantContext;
import feign.RequestInterceptor;

/**
 * Propaga a los demás microservicios del CRM el tenant (X-Tenant-Database-Code) y, si hay petición HTTP,
 * el Authorization. El tenant sale de TenantContext, así funciona igual en peticiones y en jobs
 * (mismo patrón que FeignClientInterceptor de msa-cotizaciones).
 * msa-cotizaciones y msa-params no validan el JWT; msa-security sí (llamadas desde jobs: token de servicio por definir).
 */
@Configuration
public class FeignGlobalConfig {

    public static final String TENANT_HEADER = TenantContext.TENANT_HEADER;

    @Bean
    public RequestInterceptor crmHeadersPropagationInterceptor() {
        return template -> {
            String tenant = TenantContext.getTenant();
            if (StringUtils.hasText(tenant)) {
                template.header(TENANT_HEADER, tenant);
            }
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (StringUtils.hasText(authorization)) {
                    template.header(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
        };
    }
}
