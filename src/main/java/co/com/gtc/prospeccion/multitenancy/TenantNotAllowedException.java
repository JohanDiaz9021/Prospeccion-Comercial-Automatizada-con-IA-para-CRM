package co.com.gtc.prospeccion.multitenancy;

/**
 * Se pidió una base de datos para un tenant en el que el módulo no está habilitado (prospeccion.tenants-enabled).
 */
public class TenantNotAllowedException extends RuntimeException {

    public TenantNotAllowedException(String tenantCode) {
        super("El módulo no está habilitado para el tenant " + tenantCode);
    }
}
