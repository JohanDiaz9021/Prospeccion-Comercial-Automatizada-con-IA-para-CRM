package co.com.gtc.prospeccion.prospect.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.prospect.entities.EvidenceET;

@Repository
public interface EvidenceRepository extends JpaRepository<EvidenceET, Integer> {

    /** Toda la evidencia de un contacto (la redacción solo usa estos datos). */
    List<EvidenceET> findByPmLeafletContactId(Integer pmLeafletContactId);

    /** Evidencia de varios contactos en una consulta (bandeja de borradores). */
    List<EvidenceET> findByPmLeafletContactIdIn(Collection<Integer> pmLeafletContactIds);

    /** Ya hay un contacto con este dato (por ejemplo, el mismo correo en minúsculas). */
    boolean existsByFieldAndValue(String field, String value);
}
