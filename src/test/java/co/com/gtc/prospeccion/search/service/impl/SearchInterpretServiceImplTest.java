package co.com.gtc.prospeccion.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiSearchFilters;
import co.com.gtc.prospeccion.search.dto.request.InterpretSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class SearchInterpretServiceImplTest {

    private final GeminiClient gemini = mock(GeminiClient.class);
    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final SearchInterpretServiceImpl service = new SearchInterpretServiceImpl(gemini, configs);

    private static InterpretSearchRequestDTO request(String text) {
        InterpretSearchRequestDTO r = new InterpretSearchRequestDTO();
        r.setNaturalQuery(text);
        return r;
    }

    private static GeminiSearchFilters filters(boolean understood, List<String> titles, List<String> seniorities) {
        return new GeminiSearchFilters(understood, titles, seniorities, List.of(), List.of(), List.of("51,200", "x"),
                List.of(), "gemini-2.5-flash", "search-v1");
    }

    @Test
    void usaElPerfilVigenteYLimpiaLoQuePropongaGemini() {
        TenantConfigET config = new TenantConfigET();
        config.setProfileOfInterest("{\"sectors\": {}}");
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        when(gemini.interpretSearch(eq("gerentes de TI"), eq("{\"sectors\": {}}"), anyList(), anyList()))
                .thenReturn(filters(true, List.of("Gerente de TI", "Gerente de TI"), List.of("manager", "rey")));

        SearchFiltersResponseDTO f = service.interpret(request("gerentes de TI"));

        assertThat(f.getPersonTitles()).containsExactly("Gerente de TI");
        assertThat(f.getPersonSeniorities()).containsExactly("manager");
        assertThat(f.getOrganizationNumEmployeesRanges()).containsExactly("51,200");
        assertThat(f.getOrganizationLocations()).containsExactly("Colombia");
        assertThat(f.getModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void siGeminiNoEntendioOLosFiltrosNoDicenAQuienBuscarLoAvisa() {
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        when(gemini.interpretSearch(any(), any(), anyList(), anyList()))
                .thenReturn(filters(false, List.of("CTO"), List.of()))
                .thenReturn(filters(true, List.of(), List.of("rey")));

        assertThatThrownBy(() -> service.interpret(request("hola")))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo(MessageKeys.SEARCH_NOT_UNDERSTOOD);
        assertThatThrownBy(() -> service.interpret(request("empresas medianas")))
                .extracting("messageKey").isEqualTo(MessageKeys.SEARCH_NOT_UNDERSTOOD);
    }
}
