package co.com.gtc.prospeccion.multitenancy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;
import co.com.gtc.prospeccion.config.PropertiesConfig.AwsSsmProperties;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/**
 * Lee de AWS SSM Parameter Store las credenciales de la base de un tenant, con la misma ruta que
 * msa-cotizaciones: [tenantBasePath][tenant]/url|username|password|driverclass (driverclass es opcional).
 * También lee los parámetros con las llaves de Apollo, Hunter, Gemini y el buzón.
 * Los valores nunca se registran en logs. El SsmClient se crea la primera vez que se usa, así el perfil local
 * arranca sin credenciales de AWS.
 */
@Component
public class AwsSsmTenantConfigResolver {

    static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String PROVIDER = "aws-ssm";

    /** Evita que un db_code arme rutas de SSM inesperadas. */
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AwsSsmProperties ssmProperties;
    private final Supplier<SsmClient> ssmClient;
    private final Map<String, String> parameterCache = new ConcurrentHashMap<>();

    @Autowired
    public AwsSsmTenantConfigResolver(AwsSsmProperties ssmProperties) {
        this(ssmProperties, lazy(() -> SsmClient.builder().region(Region.of(ssmProperties.getRegion())).build()));
    }

    AwsSsmTenantConfigResolver(AwsSsmProperties ssmProperties, Supplier<SsmClient> ssmClient) {
        this.ssmProperties = ssmProperties;
        this.ssmClient = ssmClient;
    }

    /** Credenciales de conexión de un tenant. */
    public record TenantDbConfig(String url, String username, String password, String driverClass) {

        /** No imprime la contraseña. */
        @Override
        public String toString() {
            return "TenantDbConfig[url=" + url + ", username=" + username + ", driverClass=" + driverClass + "]";
        }
    }

    public TenantDbConfig resolve(String tenantCode) {
        if (tenantCode == null || !TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("Código de tenant no válido");
        }
        String prefix = tenantBasePath() + tenantCode + "/";
        return new TenantDbConfig(
                required(prefix + "url"),
                required(prefix + "username"),
                required(prefix + "password"),
                fetch(prefix + "driverclass").filter(v -> !v.isBlank()).orElse(DEFAULT_DRIVER));
    }

    /** Lee un parámetro SSM (por ejemplo, la llave de Apollo). Se guarda en memoria: rotar la llave exige reiniciar. */
    public String readParameter(String parameterName) {
        return parameterCache.computeIfAbsent(parameterName, this::required);
    }

    private String tenantBasePath() {
        String base = ssmProperties.getTenantBasePath();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("Falta aws.ssm.tenant-base-path (AWS_SSM_TENANT_BASE_PATH).");
        }
        return base.endsWith("/") ? base : base + "/";
    }

    private String required(String name) {
        return fetch(name).orElseThrow(
                () -> new ExternalServiceException(PROVIDER, "No existe el parámetro " + name, null));
    }

    private Optional<String> fetch(String name) {
        try {
            GetParameterRequest request = GetParameterRequest.builder().name(name).withDecryption(true).build();
            return Optional.ofNullable(ssmClient.get().getParameter(request).parameter().value());
        } catch (ParameterNotFoundException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new ExternalServiceException(PROVIDER, "No se pudo leer el parámetro " + name, e);
        }
    }

    private static <T> Supplier<T> lazy(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T result = value;
                if (result == null) {
                    synchronized (this) {
                        result = value;
                        if (result == null) {
                            value = result = factory.get();
                        }
                    }
                }
                return result;
            }
        };
    }
}
