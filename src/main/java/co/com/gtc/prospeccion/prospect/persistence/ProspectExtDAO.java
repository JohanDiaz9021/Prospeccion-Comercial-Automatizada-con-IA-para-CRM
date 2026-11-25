package co.com.gtc.prospeccion.prospect.persistence;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.prospect.entities.ContactExtET;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.entities.LeafletExtET;

public interface ProspectExtDAO {

    LeafletExtET saveLeafletExt(LeafletExtET leafletExt);

    ContactExtET saveContactExt(ContactExtET contactExt);

    EvidenceET saveEvidence(EvidenceET evidence);

    Optional<LeafletExtET> findLeafletExtByDomain(String companyDomain);

    Page<ContactExtET> findContacts(Integer searchRunId, String status, Integer minScore, Pageable pageable);
}
