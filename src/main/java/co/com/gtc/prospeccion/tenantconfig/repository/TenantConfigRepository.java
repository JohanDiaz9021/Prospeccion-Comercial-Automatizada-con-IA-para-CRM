package co.com.gtc.prospeccion.tenantconfig.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;

@Repository
public interface TenantConfigRepository extends JpaRepository<TenantConfigET, Integer> {

    /** Versión vigente de la configuración (FLGACTIVE = 1). */
    Optional<TenantConfigET> findFirstByFlgActiveTrueOrderByIdDesc();

    /** Historial, de la más nueva a la más vieja, con quién y cuándo guardó cada versión. */
    @EntityGraph(attributePaths = { "createdBy", "createdDate" })
    Page<TenantConfigET> findAllByOrderByIdDesc(Pageable pageable);
}
