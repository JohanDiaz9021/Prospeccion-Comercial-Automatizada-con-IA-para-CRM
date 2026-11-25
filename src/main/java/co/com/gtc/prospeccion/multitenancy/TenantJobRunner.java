package co.com.gtc.prospeccion.multitenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;

/**
 * Ejecuta una tarea una vez por cada tenant habilitado (prospeccion.tenants-enabled),
 * fijando TenantContext y limpiándolo siempre en finally.
 */
@Component
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private final ProspeccionProperties properties;

    public TenantJobRunner(ProspeccionProperties properties) {
        this.properties = properties;
    }

    public void runForEachTenant(String jobName, Runnable task) {
        for (String tenant : properties.getTenantsEnabled()) {
            try {
                TenantContext.setTenant(tenant);
                task.run();
            } catch (RuntimeException e) {
                // Un tenant con error no detiene a los demás.
                log.error("Job {} falló en el tenant {}", jobName, tenant, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
