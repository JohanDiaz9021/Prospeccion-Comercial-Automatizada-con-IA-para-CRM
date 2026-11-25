package co.com.gtc.prospeccion.draft.persistence.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;

@Component
public class DraftDAOImpl implements DraftDAO {

    private final DraftRepository draftRepository;

    public DraftDAOImpl(DraftRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    @Override
    public DraftET save(DraftET draft) {
        return draftRepository.save(draft);
    }

    @Override
    public Optional<DraftET> findById(Integer id) {
        return draftRepository.findById(id);
    }

    @Override
    public Page<DraftET> findByStatus(String statusCode, Pageable pageable) {
        String code = statusCode == null || statusCode.isBlank() ? null : statusCode.strip();
        return draftRepository.findLatestVersions(DraftStatus.TABLECODE, code, pageable);
    }

    @Override
    public List<DraftET> findVersions(Integer pmLeafletContactId) {
        return draftRepository.findByPmLeafletContactIdOrderByVersionDesc(pmLeafletContactId);
    }

    @Override
    public Optional<DraftET> findLatestByContact(Integer pmLeafletContactId) {
        return draftRepository.findFirstByPmLeafletContactIdOrderByVersionDesc(pmLeafletContactId);
    }

    @Override
    public boolean hasApprovedVersion(Integer pmLeafletContactId) {
        return draftRepository.existsByPmLeafletContactIdAndStatus_TableCodeAndStatus_Code(pmLeafletContactId,
                DraftStatus.TABLECODE, DraftStatus.APPROVED);
    }
}
