package co.com.gtc.prospeccion.dashboard.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Panel del módulo (tarea 6.1).
 * <p>
 * El embudo y las tasas son <b>por cohorte</b>: toman las búsquedas creadas en el periodo (o una campaña con sus
 * ejecuciones automáticas) y siguen a sus contactos hasta hoy. Así cada paso sale del anterior y las tasas no mezclan
 * contactos de meses distintos. Los pendientes son el estado de hoy, sin importar el periodo.
 *
 * @param searchRuns búsquedas que entran en el periodo
 */
public record DashboardResponseDTO(LocalDate from, LocalDate to, Integer searchRunId, int searchRuns, Funnel funnel,
        Outcomes outcomes, Rates rates, Pending pending) {

    /** Cada paso sale del anterior. handedOff = respondieron y pasaron al vendedor con su tarea en el CRM (5.6). */
    public record Funnel(long processed, long qualified, long contacts, long drafted, long approved, long sent,
            long replied, long handedOff) {
    }

    /**
     * Lo que se quedó en el camino o sigue en curso. opened = envíos con apertura por píxel (dato aproximado, 5.4).
     */
    public record Outcomes(long discardedByAi, long withoutEmail, long excluded, long draftsToReview,
            long draftsDiscarded, long draftsExpired, long queued, long failed, long cancelled, long bounced,
            long unsubscribed, long opened) {
    }

    /** Porcentajes con un decimal; null si no hay base (por ejemplo, nada enviado todavía). */
    public record Rates(Double qualification, Double approval, Double reply, Double bounce, Double unsubscribe) {
    }

    /**
     * Estado de hoy.
     *
     * @param nextScheduled próximo turno de la cola, en hora de Colombia
     */
    public record Pending(long draftsToReview, long queued, LocalDateTime nextScheduled, long activeCampaigns,
            LastRun lastRun) {
    }

    public record LastRun(Integer id, String naturalQuery, String statusCode, LocalDate createdDate) {
    }
}
