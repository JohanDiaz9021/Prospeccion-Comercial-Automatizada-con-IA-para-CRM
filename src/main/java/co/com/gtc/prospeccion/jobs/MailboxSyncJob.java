package co.com.gtc.prospeccion.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.handoff.service.ReplyHandoffService;
import co.com.gtc.prospeccion.multitenancy.TenantJobRunner;
import co.com.gtc.prospeccion.tracking.service.TrackingService;

/**
 * Tarea 5.4: cada 15 minutos lee el buzón de prospección de cada tenant y registra respuestas, rebotes, quejas y bajas.
 * Tarea 5.6: después, quien respondió pasa al vendedor (tarea en el CRM); lo que el CRM no recibió se reintenta aquí
 * mismo en la siguiente pasada, aunque no haya buzón.
 * Se activa con prospeccion.jobs.mailbox-sync.enabled=true (apagado por defecto).
 */
@Component
@ConditionalOnProperty(name = "prospeccion.jobs.mailbox-sync.enabled", havingValue = "true")
public class MailboxSyncJob {

    private final TenantJobRunner tenantJobRunner;
    private final TrackingService trackingService;
    private final ReplyHandoffService handoffService;

    public MailboxSyncJob(TenantJobRunner tenantJobRunner, TrackingService trackingService,
            ReplyHandoffService handoffService) {
        this.tenantJobRunner = tenantJobRunner;
        this.trackingService = trackingService;
        this.handoffService = handoffService;
    }

    @Scheduled(cron = "${prospeccion.jobs.mailbox-sync.cron}", zone = "${prospeccion.timezone}")
    public void run() {
        tenantJobRunner.runForEachTenant("mailbox-sync", () -> {
            trackingService.syncMailbox();
            handoffService.handOffPending();
        });
    }
}
