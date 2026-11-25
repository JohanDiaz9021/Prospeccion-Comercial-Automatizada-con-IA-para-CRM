package co.com.gtc.prospeccion.dashboard.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.LastRun;

/** Consultas agregadas del panel (6.1). Solo lectura. */
public interface DashboardDAO {

    /** Búsqueda del periodo con su embudo guardado. */
    record RunTotals(Integer id, long newCount, long qualifiedCount) {
    }

    /**
     * Búsquedas creadas entre from y to (fechas de Colombia, inclusive).
     *
     * @param campaignId si viene, solo esa búsqueda y sus ejecuciones automáticas
     */
    List<RunTotals> findRuns(LocalDate from, LocalDate to, Integer campaignId);

    /** la_apolloseen.RESULT -> cantidad. */
    Map<String, Long> countSeenResults(Collection<Integer> runIds);

    /** Estado (CODE de 601) de la última versión del borrador de cada contacto -> cantidad. */
    Map<String, Long> countLatestDraftStatus(Collection<Integer> runIds);

    /** Estado (CODE de 602) de los envíos -> cantidad. */
    Map<String, Long> countSendStatus(Collection<Integer> runIds);

    /** Tipo de evento (CODE de 603) -> envíos con al menos uno. */
    Map<String, Long> countEventTypes(Collection<Integer> runIds);

    /** Borradores Por revisar hoy (última versión de cada contacto), de cualquier origen. */
    long countDraftsToReview();

    long countQueued();

    /** Próximo turno de la cola, en UTC. */
    Optional<LocalDateTime> nextScheduledUtc();

    long countActiveCampaigns();

    Optional<LastRun> lastRun();
}
