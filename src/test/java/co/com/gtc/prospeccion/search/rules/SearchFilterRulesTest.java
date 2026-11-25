package co.com.gtc.prospeccion.search.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;

class SearchFilterRulesTest {

    @Test
    void quitaVaciosRepetidosYValoresQueApolloNoConoce() {
        SearchFiltersResponseDTO clean = SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .personTitles(Arrays.asList(" Gerente de TI ", "gerente de ti", "", null, "IT  Manager"))
                .personSeniorities(List.of("Manager", "jefe", "c_suite", "director"))
                .organizationIndustries(List.of("Hospital & Health Care", "hospital & health care"))
                .organizationNumEmployeesRanges(List.of("201, 500", "51,200", "1-10"))
                .keywords(List.of("SAP", "sap", "a", "b", "c", "d", "e"))
                .build());

        assertThat(clean.getPersonTitles()).containsExactly("Gerente de TI", "IT Manager");
        // Orden del catálogo y en minúsculas; "jefe" no es un nivel de Apollo.
        assertThat(clean.getPersonSeniorities()).containsExactly("c_suite", "director", "manager");
        assertThat(clean.getOrganizationIndustries()).containsExactly("hospital & health care");
        assertThat(clean.getOrganizationNumEmployeesRanges()).containsExactly("51,200", "201,500");
        assertThat(clean.getKeywords()).hasSize(5).first().isEqualTo("SAP");
    }

    @Test
    void sinUbicacionSeBuscaEnColombia() {
        SearchFiltersResponseDTO clean = SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .personTitles(List.of("CTO")).build());

        assertThat(clean.getOrganizationLocations()).containsExactly("Colombia");
        assertThat(SearchFilterRules.clean(null).getOrganizationLocations()).containsExactly("Colombia");
    }

    @Test
    void soloUbicacionOTamanoNoDiceAQuienBuscar() {
        assertThat(SearchFilterRules.saysWhoToFind(SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .organizationLocations(List.of("Bogota, Colombia")).organizationNumEmployeesRanges(List.of("51,200"))
                .build()))).isFalse();
        assertThat(SearchFilterRules.saysWhoToFind(SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .organizationIndustries(List.of("retail")).build()))).isTrue();
        assertThat(SearchFilterRules.saysWhoToFind(SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .keywords(List.of("SAP")).build()))).isTrue();
    }

    @Test
    void armaLaPeticionDeApolloConPalabrasClaveYDominiosExcluidos() {
        SearchFiltersResponseDTO clean = SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .personTitles(List.of("CTO")).keywords(List.of("SAP", "IPS")).build());

        ApolloPeopleSearchRequest request = SearchFilterRules.toApollo(clean, Set.of("competidor.com"));

        assertThat(request.getPersonTitles()).containsExactly("CTO");
        assertThat(request.getQKeywords()).isEqualTo("SAP IPS");
        assertThat(request.getOrganizationLocations()).containsExactly("Colombia");
        assertThat(request.getNotOrganizationWebsitesList()).containsExactly("competidor.com");
        // Las listas vacías no viajan.
        assertThat(request.getPersonSeniorities()).isNull();
        assertThat(SearchFilterRules.toApollo(clean, Set.of()).getNotOrganizationWebsitesList()).isNull();
    }

    @Test
    void lasListasDelPerfilQuedanEnMinusculasYSinWww() {
        ProfileExclusions lists = ProfileExclusions.from("""
                {"exclusions": {"competitorDomains": ["https://www.Competidor.com/inicio", "  "],
                 "excludedCompanyDomains": ["cliente.com."], "ownDomains": ["ventas@MiEmpresa.com"]}}
                """, new ObjectMapper());

        assertThat(lists.competitorDomains()).containsExactly("competidor.com");
        assertThat(lists.notToSearch()).containsExactlyInAnyOrder("competidor.com", "cliente.com");
        assertThat(lists.ownDomains()).containsExactly("miempresa.com");
        assertThat(ProfileExclusions.from("no es json", new ObjectMapper())).isEqualTo(ProfileExclusions.NONE);
        assertThat(ProfileExclusions.from(null, new ObjectMapper()).notToSearch()).isEmpty();
    }
}
