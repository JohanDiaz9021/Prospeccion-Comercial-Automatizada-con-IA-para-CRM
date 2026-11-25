package co.com.gtc.prospeccion.dev.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;

import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloOrganizationDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.service.EmailDiscoveryService;

class DevToolsControllerTest {

    private final ApolloClient apollo = mock(ApolloClient.class);
    private final EmailDiscoveryService discovery = mock(EmailDiscoveryService.class);
    private final DevToolsController controller = new DevToolsController(apollo, discovery);

    @Test
    void soloExisteConElPerfilLocal() {
        // En QA y producción no se registra: gasta créditos reales.
        assertThat(DevToolsController.class.getAnnotation(Profile.class).value()).containsExactly("local");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buscaEnApolloConLosFiltrosYDevuelveFilasSencillas() {
        ApolloSearchPersonDTO persona = new ApolloSearchPersonDTO();
        persona.setId("abc123");
        persona.setFirstName("Ana");
        persona.setTitle("Gerente de TI");
        when(apollo.searchPage(any(), anyInt(), anyInt()))
                .thenReturn(new ApolloSearchPage(1, 2, 10, List.of(persona), ApolloSearchPage.ApolloRateLimit.UNKNOWN));

        Map<String, Object> data = (Map<String, Object>) controller
                .searchApollo(new DevToolsController.ApolloSearchRequest("Gerente de TI", " ", "51,200", null, 2))
                .getBody().getData();

        ArgumentCaptor<ApolloPeopleSearchRequest> filtros = ArgumentCaptor.forClass(ApolloPeopleSearchRequest.class);
        verify(apollo).searchPage(filtros.capture(), eq(2), eq(10));
        assertThat(filtros.getValue().getPersonTitles()).containsExactly("Gerente de TI");
        assertThat(filtros.getValue().getPersonLocations()).isNull(); // en blanco no viaja
        assertThat(filtros.getValue().getOrganizationNumEmployeesRanges()).containsExactly("51,200");
        assertThat(data).containsEntry("total", 1);
        assertThat((List<Map<String, Object>>) data.get("people")).singleElement()
                .satisfies(fila -> assertThat(fila).containsEntry("id", "abc123").containsEntry("firstName", "Ana"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void consigueElCorreoSinCampanaYDevuelveElGasto() {
        ApolloPersonDTO persona = new ApolloPersonDTO();
        persona.setFirstName("Ana");
        ApolloOrganizationDTO empresa = new ApolloOrganizationDTO();
        empresa.setName("Empresa Demo");
        empresa.setPrimaryDomain("empresa-demo.co");
        persona.setOrganization(empresa);
        when(discovery.discover(eq("abc123"), any())).thenReturn(EmailDiscoveryResult.builder()
                .outcome(EmailDiscoveryResult.Outcome.FOUND).email("ana@empresa-demo.co").source("HUNTER")
                .verification("valid").score(95).person(persona).apolloCredits(1).hunterSearchCredits(1)
                .hunterVerificationCredits(0.5).build());

        Map<String, Object> data = (Map<String, Object>) controller
                .discoverEmail(new DevToolsController.EmailDiscoveryRequest(" abc123 ")).getBody().getData();

        verify(discovery).discover("abc123", CreditContext.NONE);
        assertThat(data).containsEntry("outcome", EmailDiscoveryResult.Outcome.FOUND)
                .containsEntry("email", "ana@empresa-demo.co")
                .containsEntry("hunterVerificationCredits", 0.5);
        assertThat((Map<String, Object>) data.get("person")).containsEntry("domain", "empresa-demo.co");
    }

    @Test
    @SuppressWarnings("unchecked")
    void soloHunterPasaLaPersonaEscritaSinCampana() {
        when(discovery.discoverWithHunter("Ana", "Ficticia", "empresa-demo.co", CreditContext.NONE))
                .thenReturn(EmailDiscoveryResult.builder().outcome(EmailDiscoveryResult.Outcome.NOT_FOUND).build());

        Map<String, Object> data = (Map<String, Object>) controller
                .discoverEmailWithHunter(new DevToolsController.HunterEmailRequest("Ana", "Ficticia", "empresa-demo.co"))
                .getBody().getData();

        assertThat(data).containsEntry("outcome", EmailDiscoveryResult.Outcome.NOT_FOUND).doesNotContainKey("person");
    }
}
