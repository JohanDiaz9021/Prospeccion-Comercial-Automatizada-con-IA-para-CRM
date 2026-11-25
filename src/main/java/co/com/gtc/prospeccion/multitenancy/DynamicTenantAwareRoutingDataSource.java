package co.com.gtc.prospeccion.multitenancy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Enruta cada conexión a la base del tenant que esté en TenantContext. El DataSource de cada tenant se crea
 * la primera vez que se necesita (credenciales en SSM) y se reutiliza; al apagar el servicio se cierran todos.
 * Solo atiende a los tenants habilitados. Sin tenant en TenantContext usa el DataSource por defecto
 * (solo existe en el perfil local); en qa/prod eso es un error.
 */
public class DynamicTenantAwareRoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicTenantAwareRoutingDataSource.class);

    private final Map<String, DataSource> tenantDataSources = new ConcurrentHashMap<>();
    private final Function<String, DataSource> dataSourceFactory;
    private final Predicate<String> tenantEnabled;

    public DynamicTenantAwareRoutingDataSource(Function<String, DataSource> dataSourceFactory,
            Predicate<String> tenantEnabled) {
        this.dataSourceFactory = dataSourceFactory;
        this.tenantEnabled = tenantEnabled;
    }

    /** Registra un DataSource ya creado (perfil local). */
    public void registerTenant(String tenantCode, DataSource dataSource) {
        tenantDataSources.put(tenantCode, dataSource);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getTenant();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        String tenant = TenantContext.getTenant();
        if (tenant == null) {
            DataSource defaultDataSource = getResolvedDefaultDataSource();
            if (defaultDataSource == null) {
                throw new IllegalStateException(
                        "No hay tenant en TenantContext: la petición o el job debe fijarlo antes de usar la base.");
            }
            return defaultDataSource;
        }
        if (!tenantEnabled.test(tenant)) {
            throw new TenantNotAllowedException(tenant);
        }
        return tenantDataSources.computeIfAbsent(tenant, code -> {
            log.info("Creando el pool de conexiones del tenant {}", code);
            return dataSourceFactory.apply(code);
        });
    }

    @Override
    public void destroy() {
        tenantDataSources.forEach((tenant, dataSource) -> {
            if (dataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("No se pudo cerrar el pool del tenant {}", tenant, e);
                }
            }
        });
        tenantDataSources.clear();
    }
}
