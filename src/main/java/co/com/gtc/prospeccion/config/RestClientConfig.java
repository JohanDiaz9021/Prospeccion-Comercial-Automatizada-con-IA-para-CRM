package co.com.gtc.prospeccion.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;

/**
 * Un RestClient por proveedor externo (sin SDKs de terceros).
 * Las llaves NO se configuran aquí: se leen de SSM al llamar (TODO en cada cliente).
 * Se inyectan por nombre de bean: @Qualifier(RestClientConfig.APOLLO), etc.
 */
@Configuration
public class RestClientConfig {

    public static final String APOLLO = "apolloRestClient";
    public static final String HUNTER = "hunterRestClient";
    public static final String GEMINI = "geminiRestClient";

    @Bean(name = APOLLO)
    public RestClient apolloRestClient(RestClient.Builder builder, IntegrationsProperties props) {
        return build(builder, props.getApollo());
    }

    @Bean(name = HUNTER)
    public RestClient hunterRestClient(RestClient.Builder builder, IntegrationsProperties props) {
        return build(builder, props.getHunter());
    }

    @Bean(name = GEMINI)
    public RestClient geminiRestClient(RestClient.Builder builder, IntegrationsProperties props) {
        return build(builder, props.getGemini());
    }

    private static RestClient build(RestClient.Builder builder, Provider provider) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(provider.getConnectTimeout())
                .withReadTimeout(provider.getReadTimeout());
        // TODO: reintentos y timeouts con Resilience4j (CircuitBreakerFactory) y logging sin datos personales.
        return builder.clone()
                .baseUrl(provider.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
