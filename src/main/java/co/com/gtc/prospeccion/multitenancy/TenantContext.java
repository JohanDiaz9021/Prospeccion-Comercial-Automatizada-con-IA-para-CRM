package co.com.gtc.prospeccion.multitenancy;

/**
 * Tenant (db_code) de la petición o del job en curso.
 * Siempre limpiar en finally para no filtrar el tenant entre hilos reutilizados.
 */
public final class TenantContext {

    /** Header con el que viaja el tenant entre los microservicios del CRM (lo pone el gateway). */
    public static final String TENANT_HEADER = "X-Tenant-Database-Code";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenant(String tenantCode) {
        CURRENT.set(tenantCode);
    }

    public static String getTenant() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
