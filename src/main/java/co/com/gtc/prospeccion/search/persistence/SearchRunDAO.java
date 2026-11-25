package co.com.gtc.prospeccion.search.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.search.entities.ApolloSeenET;
import co.com.gtc.prospeccion.search.entities.SearchRunET;

public interface SearchRunDAO {

    SearchRunET save(SearchRunET searchRun);

    Optional<SearchRunET> findById(Integer id);

    /** Con estado y fecha cargados. */
    Optional<SearchRunET> findWithStatus(Integer id);

    /** La más reciente primero, con estado y fecha cargados. */
    Page<SearchRunET> findAll(Pageable pageable);

    List<SearchRunET> findScheduled();

    /** Día de la última ejecución de la campaña (ella misma o una automática); vacío si nunca corrió. */
    Optional<LocalDate> lastExecutionDate(Integer campaignId);

    boolean wasApolloPersonSeen(String apolloPersonId);

    /** Cuáles de estos IDs de Apollo ya se procesaron. */
    Set<String> findSeen(Collection<String> apolloPersonIds);

    ApolloSeenET saveSeen(ApolloSeenET seen);

    /** Por búsqueda: resultado -> cantidad. */
    Map<Integer, Map<String, Long>> countResults(Collection<Integer> searchRunIds);

    List<ApolloSeenET> findSeenByRun(Integer searchRunId);
}
