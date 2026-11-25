package co.com.gtc.prospeccion.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.multitenancy.TenantJobRunner;
import co.com.gtc.prospeccion.search.service.ProspectSearchService;

/**
 * Tareas 3.2 y 3.4: cada día hábil a las 6:00 (hora de Colombia) repite las campañas activas de cada tenant a las que
 * les toca (diarias: cada día; semanales: 7 días después de su última ejecución), con sus filtros y el perfil vigente.
 * Los borradores nuevos quedan en la bandeja antes de que empiece la jornada de envío (7:00). La hora se cambia con
 * SCHEDULED_SEARCH_CRON.
 * Se activa con prospeccion.jobs.scheduled-search.enabled=true (apagado por defecto).
 */
@Component
@ConditionalOnProperty(name = "prospeccion.jobs.scheduled-search.enabled", havingValue = "true")
public class ScheduledSearchJob {

    private final TenantJobRunner tenantJobRunner;
    private final ProspectSearchService prospectSearchService;

    public ScheduledSearchJob(TenantJobRunner tenantJobRunner, ProspectSearchService prospectSearchService) {
        this.tenantJobRunner = tenantJobRunner;
        this.prospectSearchService = prospectSearchService;
    }

    @Scheduled(cron = "${prospeccion.jobs.scheduled-search.cron}", zone = "${prospeccion.timezone}")
    public void run() {
        tenantJobRunner.runForEachTenant("scheduled-search", () -> prospectSearchService.rerunScheduledSearches(false));
    }
}
