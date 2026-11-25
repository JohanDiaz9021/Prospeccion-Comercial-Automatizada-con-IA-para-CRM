package co.com.gtc.prospeccion.integration.apollo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.integration.Sleeper;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;

/**
 * Prueba contra la API real de Apollo. Solo corre si existe la variable APOLLO_API_KEY (no en CI).
 * Pide una página de 5 personas: People API Search no gasta créditos (sí cuenta para el límite de tasa).
 * <pre>
 * $env:APOLLO_API_KEY = "..."; .\gradlew.bat test --tests "*ApolloClientLiveTest"
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "APOLLO_API_KEY", matches = ".+")
class ApolloClientLiveTest {

    @Test
    void buscaGerentesDeTiEnBogota() {
        Provider properties = new Provider();
        properties.setBaseUrl("https://api.apollo.io/api/v1");
        properties.setMaxRetries(1);
        ApolloClient client = new ApolloClient(RestClient.builder().baseUrl(properties.getBaseUrl()).build(),
                properties, () -> System.getenv("APOLLO_API_KEY"), new ObjectMapper(), Sleeper.REAL);

        ApolloSearchPage page = client.searchPage(ApolloPeopleSearchRequest.builder()
                .personTitles(List.of("Gerente de TI", "IT Manager"))
                .personLocations(List.of("Bogota, Colombia"))
                .build(), 1, 5);

        assertThat(page.totalEntries()).isPositive();
        assertThat(page.people()).isNotEmpty().allSatisfy(p -> assertThat(p.getId()).isNotBlank());
        System.out.printf("Apollo: %d gerentes; cupo restante minuto/hora/día: %s%n", page.totalEntries(),
                page.rateLimit());
    }
}
