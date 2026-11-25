package co.com.gtc.prospeccion.crm.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.crm.entities.MasterTableET;

@Repository
public interface MasterTableRepository extends JpaRepository<MasterTableET, Integer> {

    /**
     * Mismo criterio que msa-cotizaciones (findFirstByTableCodeAndCode): puede haber una fila por idioma.
     */
    Optional<MasterTableET> findFirstByTableCodeAndCode(Integer tableCode, String code);
}
