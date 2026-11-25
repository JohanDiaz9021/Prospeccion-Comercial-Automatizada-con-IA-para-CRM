package co.com.gtc.prospeccion.search.service.impl;

import org.springframework.stereotype.Service;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiSearchFilters;
import co.com.gtc.prospeccion.search.dto.request.InterpretSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;
import co.com.gtc.prospeccion.search.rules.SearchFilterRules;
import co.com.gtc.prospeccion.search.service.SearchInterpretService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Tarea 3.2: Gemini convierte la búsqueda en filtros de Apollo y el código los limpia (SearchFilterRules). No guarda
 * nada ni gasta créditos de Apollo: el usuario revisa los filtros antes de buscar.
 */
@Service
public class SearchInterpretServiceImpl implements SearchInterpretService {

    private final GeminiClient gemini;
    private final TenantConfigRepository configRepository;

    public SearchInterpretServiceImpl(GeminiClient gemini, TenantConfigRepository configRepository) {
        this.gemini = gemini;
        this.configRepository = configRepository;
    }

    @Override
    public SearchFiltersResponseDTO interpret(InterpretSearchRequestDTO request) {
        String profile = configRepository.findFirstByFlgActiveTrueOrderByIdDesc()
                .map(TenantConfigET::getProfileOfInterest).orElse(null);
        GeminiSearchFilters proposed = gemini.interpretSearch(request.getNaturalQuery(), profile,
                SearchFilterRules.SENIORITIES, SearchFilterRules.EMPLOYEE_RANGES);

        SearchFiltersResponseDTO clean = SearchFilterRules.clean(SearchFiltersResponseDTO.builder()
                .personTitles(proposed.personTitles())
                .personSeniorities(proposed.personSeniorities())
                .organizationLocations(proposed.organizationLocations())
                .organizationIndustries(proposed.organizationIndustries())
                .organizationNumEmployeesRanges(proposed.organizationNumEmployeesRanges())
                .keywords(proposed.keywords())
                .model(proposed.model())
                .build());
        if (!proposed.understood() || !SearchFilterRules.saysWhoToFind(clean)) {
            throw new BusinessException(MessageKeys.SEARCH_NOT_UNDERSTOOD,
                    "La búsqueda no dice a quién buscar (cargo, sector o palabra clave)");
        }
        return clean;
    }
}
