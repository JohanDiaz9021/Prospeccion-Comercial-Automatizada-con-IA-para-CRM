package co.com.gtc.prospeccion.prospect.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.prospect.entities.LeafletExtET;

@Repository
public interface LeafletExtRepository extends JpaRepository<LeafletExtET, Integer> {

    Optional<LeafletExtET> findByCompanyDomain(String companyDomain);

    boolean existsByCompanyDomain(String companyDomain);

    boolean existsByProviderId(String providerId);
}
