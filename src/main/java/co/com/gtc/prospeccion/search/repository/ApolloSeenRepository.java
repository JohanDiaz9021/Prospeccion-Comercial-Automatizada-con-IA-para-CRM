package co.com.gtc.prospeccion.search.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.search.entities.ApolloSeenET;

@Repository
public interface ApolloSeenRepository extends JpaRepository<ApolloSeenET, Integer> {

    boolean existsByProviderId(String providerId);

    /** Cuáles de estos IDs de Apollo ya se procesaron en alguna búsqueda. */
    @Query("select s.providerId from ApolloSeenET s where s.providerId in :ids")
    Set<String> findSeenProviderIds(@Param("ids") Collection<String> providerIds);

    /** Conteo por resultado de cada búsqueda: [searchRunId, result, cantidad]. */
    @Query("select s.searchRunId, s.result, count(s) from ApolloSeenET s where s.searchRunId in :runIds "
            + "group by s.searchRunId, s.result")
    List<Object[]> countResults(@Param("runIds") Collection<Integer> searchRunIds);

    List<ApolloSeenET> findBySearchRunIdOrderByIdAsc(Integer searchRunId);
}
