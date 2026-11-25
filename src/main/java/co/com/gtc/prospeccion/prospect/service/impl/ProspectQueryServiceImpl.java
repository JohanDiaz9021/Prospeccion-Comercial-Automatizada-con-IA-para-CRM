package co.com.gtc.prospeccion.prospect.service.impl;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import co.com.gtc.prospeccion.prospect.dto.response.ProspectCardResponseDTO;
import co.com.gtc.prospeccion.prospect.service.ProspectQueryService;

@Service
public class ProspectQueryServiceImpl implements ProspectQueryService {

    @Override
    public Page<ProspectCardResponseDTO> list(Integer searchRunId, String status, Integer minScore,
            Pageable pageable) {
        // TODO: ProspectExtDAO.findContacts + ProspectMapper.toCard.
        throw new UnsupportedOperationException("TODO");
    }
}
