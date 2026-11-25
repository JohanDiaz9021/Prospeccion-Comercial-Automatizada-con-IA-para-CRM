package co.com.gtc.prospeccion.suppression.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.suppression.entities.SuppressionET;

@Repository
public interface SuppressionRepository extends JpaRepository<SuppressionET, Integer> {

    /** EMAIL se guarda en minúsculas. */
    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = { "reason", "createdDate" })
    Page<SuppressionET> findAllByOrderByIdDesc(Pageable pageable);
}
