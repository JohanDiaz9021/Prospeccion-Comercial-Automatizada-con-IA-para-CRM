package co.com.gtc.prospeccion.multitenancy;

import java.util.HashMap;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import co.com.gtc.prospeccion.config.PropertiesConfig.LocalTenant;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.TenantPool;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver.TenantDbConfig;

/**
 * DataSource multi-tenant: un pool Hikari por base de tenant, creado bajo demanda.
 * En qa/prod las credenciales vienen de SSM. En el perfil local se registra además un tenant leído de
 * variables de entorno (prospeccion.local-tenant.*), que también es el DataSource por defecto.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    public DataSource dataSource(ProspeccionProperties properties, AwsSsmTenantConfigResolver resolver) {
        TenantPool pool = properties.getTenantPool();
        DynamicTenantAwareRoutingDataSource routing = new DynamicTenantAwareRoutingDataSource(
                tenant -> createPool(tenant, resolver.resolve(tenant), pool),
                properties::isTenantEnabled);

        LocalTenant local = properties.getLocalTenant();
        if (local.isEnabled()) {
            DataSource localDataSource = createPool(local.getCode(), new TenantDbConfig(
                    local.getUrl(), local.getUsername(), local.getPassword(), local.getDriverClass()), pool);
            routing.registerTenant(local.getCode(), localDataSource);
            routing.setDefaultTargetDataSource(localDataSource);
        }

        // Los DataSource de los tenants se crean bajo demanda en determineTargetDataSource.
        routing.setTargetDataSources(new HashMap<>());
        return routing;
    }

    static HikariDataSource createPool(String tenant, TenantDbConfig config, TenantPool pool) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(config.driverClass())
                .url(config.url())
                .username(config.username())
                .password(config.password())
                .build();
        dataSource.setPoolName("tenant-" + tenant);
        dataSource.setMaximumPoolSize(pool.getMaximumPoolSize());
        dataSource.setMinimumIdle(pool.getMinimumIdle());
        return dataSource;
    }
}
