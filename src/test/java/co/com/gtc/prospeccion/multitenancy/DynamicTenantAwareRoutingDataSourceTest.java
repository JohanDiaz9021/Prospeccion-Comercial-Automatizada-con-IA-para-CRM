package co.com.gtc.prospeccion.multitenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DynamicTenantAwareRoutingDataSourceTest {

    private final AtomicInteger created = new AtomicInteger();
    private final DataSource tenantA = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));

    private DynamicTenantAwareRoutingDataSource routing(DataSource defaultDataSource) {
        DynamicTenantAwareRoutingDataSource routing = new DynamicTenantAwareRoutingDataSource(tenant -> {
            created.incrementAndGet();
            return tenantA;
        }, Set.of("tenant_a")::contains);
        if (defaultDataSource != null) {
            routing.setDefaultTargetDataSource(defaultDataSource);
        }
        routing.setTargetDataSources(new HashMap<>());
        routing.afterPropertiesSet();
        return routing;
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    void creaElDataSourceDelTenantUnaSolaVez() {
        DynamicTenantAwareRoutingDataSource routing = routing(null);
        TenantContext.setTenant("tenant_a");

        assertThat(routing.determineTargetDataSource()).isSameAs(tenantA);
        assertThat(routing.determineTargetDataSource()).isSameAs(tenantA);
        assertThat(created).hasValue(1);
    }

    @Test
    void rechazaTenantNoHabilitado() {
        DynamicTenantAwareRoutingDataSource routing = routing(null);
        TenantContext.setTenant("tenant_b");

        assertThatThrownBy(routing::determineTargetDataSource).isInstanceOf(TenantNotAllowedException.class);
        assertThat(created).hasValue(0);
    }

    @Test
    void sinTenantUsaElDataSourcePorDefectoDelPerfilLocal() {
        DataSource local = mock(DataSource.class);

        assertThat(routing(local).determineTargetDataSource()).isSameAs(local);
    }

    @Test
    void sinTenantNiDataSourcePorDefectoFalla() {
        assertThatThrownBy(routing(null)::determineTargetDataSource).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cierraLosPoolsAlApagar() throws Exception {
        DynamicTenantAwareRoutingDataSource routing = routing(null);
        TenantContext.setTenant("tenant_a");
        routing.determineTargetDataSource();

        routing.destroy();

        verify((AutoCloseable) tenantA).close();
    }
}
