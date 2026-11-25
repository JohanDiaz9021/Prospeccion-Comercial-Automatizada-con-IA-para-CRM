package co.com.gtc.prospeccion.prospect.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.prospect.dto.response.ProspectCardResponseDTO;

/**
 * Consulta de prospectos para la pantalla de resultados.
 */
public interface ProspectQueryService {

    Page<ProspectCardResponseDTO> list(Integer searchRunId, String status, Integer minScore, Pageable pageable);
}
