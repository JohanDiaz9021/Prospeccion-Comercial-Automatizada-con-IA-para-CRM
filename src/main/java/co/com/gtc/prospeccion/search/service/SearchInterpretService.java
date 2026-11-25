package co.com.gtc.prospeccion.search.service;

import co.com.gtc.prospeccion.search.dto.request.InterpretSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchFiltersResponseDTO;

/**
 * Convierte la búsqueda en lenguaje natural en filtros de Apollo (con Gemini).
 */
public interface SearchInterpretService {

    SearchFiltersResponseDTO interpret(InterpretSearchRequestDTO request);
}
