package co.com.gtc.prospeccion.multitenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;
import co.com.gtc.prospeccion.config.PropertiesConfig.AwsSsmProperties;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver.TenantDbConfig;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

class AwsSsmTenantConfigResolverTest {

    private final SsmClient ssm = mock(SsmClient.class);
    private final Map<String, String> parametros = new HashMap<>();
    private AwsSsmTenantConfigResolver resolver;

    @BeforeEach
    void preparar() {
        AwsSsmProperties properties = new AwsSsmProperties();
        properties.setRegion("us-east-1");
        // Sin "/" final: el resolver lo agrega.
        properties.setTenantBasePath("/config/gtcproject/tenant_db");
        resolver = new AwsSsmTenantConfigResolver(properties, () -> ssm);

        when(ssm.getParameter(any(GetParameterRequest.class))).thenAnswer(invocation -> {
            GetParameterRequest request = invocation.getArgument(0);
            assertThat(request.withDecryption()).isTrue();
            String value = parametros.get(request.name());
            if (value == null) {
                throw ParameterNotFoundException.builder().message("no existe").build();
            }
            return GetParameterResponse.builder().parameter(Parameter.builder().value(value).build()).build();
        });
    }

    @Test
    void armaLasCredencialesConLaRutaDelCrm() {
        parametros.put("/config/gtcproject/tenant_db/gtc/url", "jdbc:mysql://db/gtc");
        parametros.put("/config/gtcproject/tenant_db/gtc/username", "usuario");
        parametros.put("/config/gtcproject/tenant_db/gtc/password", "clave");
        parametros.put("/config/gtcproject/tenant_db/gtc/driverclass", "org.mariadb.jdbc.Driver");

        TenantDbConfig config = resolver.resolve("gtc");

        assertThat(config).isEqualTo(
                new TenantDbConfig("jdbc:mysql://db/gtc", "usuario", "clave", "org.mariadb.jdbc.Driver"));
        assertThat(config.toString()).doesNotContain("clave");
    }

    @Test
    void sinDriverclassUsaElDriverDeMysql() {
        parametros.put("/config/gtcproject/tenant_db/gtc/url", "jdbc:mysql://db/gtc");
        parametros.put("/config/gtcproject/tenant_db/gtc/username", "usuario");
        parametros.put("/config/gtcproject/tenant_db/gtc/password", "clave");

        assertThat(resolver.resolve("gtc").driverClass()).isEqualTo(AwsSsmTenantConfigResolver.DEFAULT_DRIVER);
    }

    @Test
    void sinUrlFallaConErrorDeProveedorExterno() {
        assertThatThrownBy(() -> resolver.resolve("gtc")).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void rechazaCodigosDeTenantQueArmanRutasRaras() {
        assertThatThrownBy(() -> resolver.resolve("../otro")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guardaEnMemoriaLosParametrosDeLlaves() {
        parametros.put("/config/gtcproject/prospeccion/gemini-api-key", "llave");

        assertThat(resolver.readParameter("/config/gtcproject/prospeccion/gemini-api-key")).isEqualTo("llave");
        assertThat(resolver.readParameter("/config/gtcproject/prospeccion/gemini-api-key")).isEqualTo("llave");
        verify(ssm, times(1)).getParameter(any(GetParameterRequest.class));
    }
}
