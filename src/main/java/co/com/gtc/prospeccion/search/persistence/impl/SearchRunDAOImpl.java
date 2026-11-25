package co.com.gtc.prospeccion.search.persistence.impl;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.search.entities.ApolloSeenET;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import co.com.gtc.prospeccion.search.persistence.SearchRunDAO;
import co.com.gtc.prospeccion.search.repository.ApolloSeenRepository;
import co.com.gtc.prospeccion.search.repository.SearchRunRepository;

@Component
public class SearchRunDAOImpl implements SearchRunDAO {

    private final SearchRunRepository searchRunRepository;
    private final ApolloSeenRepository apolloSeenRepository;

    public SearchRunDAOImpl(SearchRunRepository searchRunRepository, ApolloSeenRepository apolloSeenRepository) {
        this.searchRunRepository = searchRunRepository;
        this.apolloSeenRepository = apolloSeenRepository;
    }

    @Override
    public SearchRunET save(SearchRunET searchRun) {
        return searchRunRepository.save(searchRun);
    }

    @Override
    public Optional<SearchRunET> findById(Integer id) {
        return searchRunRepository.findById(id);
    }

    @Override
    public Optional<SearchRunET> findWithStatus(Integer id) {
        return searchRunRepository.findWithStatusById(id);
    }

    @Override
    public Page<SearchRunET> findAll(Pageable pageable) {
        return searchRunRepository.findAllByOrderByIdDesc(pageable);
    }

    @Override
    public List<SearchRunET> findScheduled() {
        return searchRunRepository.findByFlgScheduledTrueOrderByIdAsc();
    }

    @Override
    public Optional<LocalDate> lastExecutionDate(Integer campaignId) {
        return Optional.ofNullable(searchRunRepository.findLastExecutionDate(campaignId));
    }

    @Override
    public boolean wasApolloPersonSeen(String apolloPersonId) {
        return apolloSeenRepository.existsByProviderId(apolloPersonId);
    }

    @Override
    public Set<String> findSeen(Collection<String> apolloPersonIds) {
        return apolloPersonIds.isEmpty() ? Set.of() : apolloSeenRepository.findSeenProviderIds(apolloPersonIds);
    }

    @Override
    public ApolloSeenET saveSeen(ApolloSeenET seen) {
        return apolloSeenRepository.save(seen);
    }

    @Override
    public Map<Integer, Map<String, Long>> countResults(Collection<Integer> searchRunIds) {
        Map<Integer, Map<String, Long>> counts = new HashMap<>();
        if (searchRunIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : apolloSeenRepository.countResults(searchRunIds)) {
            counts.computeIfAbsent((Integer) row[0], k -> new LinkedHashMap<>())
                    .put((String) row[1], ((Number) row[2]).longValue());
        }
        return counts;
    }

    @Override
    public List<ApolloSeenET> findSeenByRun(Integer searchRunId) {
        return apolloSeenRepository.findBySearchRunIdOrderByIdAsc(searchRunId);
    }
}
