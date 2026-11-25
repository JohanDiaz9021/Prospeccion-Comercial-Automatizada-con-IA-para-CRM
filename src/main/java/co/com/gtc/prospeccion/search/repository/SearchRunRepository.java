package co.com.gtc.prospeccion.search.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.search.entities.SearchRunET;

@Repository
public interface SearchRunRepository extends JpaRepository<SearchRunET, Integer> {

    /** Con estado y fecha cargados: el servicio no deja la sesión abierta para la vista (open-in-view = false). */
    @EntityGraph(attributePaths = { "status", "createdDate" })
    Page<SearchRunET> findAllByOrderByIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = { "status", "createdDate" })
    Optional<SearchRunET> findWithStatusById(Integer id);

    /** Fecha de la última ejecución de una campaña: la campaña misma o una de sus ejecuciones automáticas. */
    @Query("select max(r.createdDate.date) from SearchRunET r where r.id = :id or r.parentSearchRunId = :id")
    LocalDate findLastExecutionDate(@Param("id") Integer campaignId);

    /** Campañas activas, la más antigua primero. */
    List<SearchRunET> findByFlgScheduledTrueOrderByIdAsc();
}
