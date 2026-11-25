package co.com.gtc.prospeccion.prospect.persistence.impl;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.prospect.entities.ContactExtET;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.entities.LeafletExtET;
import co.com.gtc.prospeccion.prospect.persistence.ProspectExtDAO;
import co.com.gtc.prospeccion.prospect.repository.ContactExtRepository;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.prospect.repository.LeafletExtRepository;

@Component
public class ProspectExtDAOImpl implements ProspectExtDAO {

    private final LeafletExtRepository leafletExtRepository;
    private final ContactExtRepository contactExtRepository;
    private final EvidenceRepository evidenceRepository;

    public ProspectExtDAOImpl(LeafletExtRepository leafletExtRepository, ContactExtRepository contactExtRepository,
            EvidenceRepository evidenceRepository) {
        this.leafletExtRepository = leafletExtRepository;
        this.contactExtRepository = contactExtRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    public LeafletExtET saveLeafletExt(LeafletExtET leafletExt) {
        return leafletExtRepository.save(leafletExt);
    }

    @Override
    public ContactExtET saveContactExt(ContactExtET contactExt) {
        return contactExtRepository.save(contactExt);
    }

    @Override
    public EvidenceET saveEvidence(EvidenceET evidence) {
        return evidenceRepository.save(evidence);
    }

    @Override
    public Optional<LeafletExtET> findLeafletExtByDomain(String companyDomain) {
        return leafletExtRepository.findByCompanyDomain(companyDomain);
    }

    @Override
    public Page<ContactExtET> findContacts(Integer searchRunId, String status, Integer minScore, Pageable pageable) {
        // TODO: consulta con filtros opcionales (Specification o @Query).
        throw new UnsupportedOperationException("TODO");
    }
}
