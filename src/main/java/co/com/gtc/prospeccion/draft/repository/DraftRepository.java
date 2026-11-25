package co.com.gtc.prospeccion.draft.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.draft.entities.DraftET;

@Repository
public interface DraftRepository extends JpaRepository<DraftET, Integer> {

    /** Última versión del borrador de un contacto. */
    Optional<DraftET> findFirstByPmLeafletContactIdOrderByVersionDesc(Integer pmLeafletContactId);

    /** Si alguna versión del contacto está en ese estado (por ejemplo, ya aprobada). */
    boolean existsByPmLeafletContactIdAndStatus_TableCodeAndStatus_Code(Integer pmLeafletContactId, Integer tableCode,
            String code);

    /** Bandeja (5.1): la última versión de cada contacto, opcionalmente de un estado, la más reciente primero. */
    @Query(value = """
            select d from DraftET d join fetch d.status s
            where s.tableCode = :tableCode and (:code is null or s.code = :code)
              and d.version = (select max(d2.version) from DraftET d2 where d2.pmLeafletContactId = d.pmLeafletContactId)
            order by d.id desc""",
            countQuery = """
            select count(d) from DraftET d
            where d.status.tableCode = :tableCode and (:code is null or d.status.code = :code)
              and d.version = (select max(d2.version) from DraftET d2 where d2.pmLeafletContactId = d.pmLeafletContactId)""")
    Page<DraftET> findLatestVersions(@Param("tableCode") Integer tableCode, @Param("code") String code,
            Pageable pageable);

    /** Todas las versiones del borrador de un contacto, la más nueva primero. */
    List<DraftET> findByPmLeafletContactIdOrderByVersionDesc(Integer pmLeafletContactId);

    /** Cuántas versiones tiene cada contacto: [PM_LEAFLETCONTACTID, cantidad]. */
    @Query("""
            select d.pmLeafletContactId, count(d) from DraftET d
            where d.pmLeafletContactId in :contactIds group by d.pmLeafletContactId""")
    List<Object[]> countVersions(@Param("contactIds") Collection<Integer> contactIds);
}
