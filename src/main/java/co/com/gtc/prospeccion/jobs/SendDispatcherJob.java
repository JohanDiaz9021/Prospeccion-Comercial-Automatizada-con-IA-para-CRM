package co.com.gtc.prospeccion.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.multitenancy.TenantJobRunner;
import co.com.gtc.prospeccion.sending.service.SendQueueService;

/**
 * Cada 5 minutos en horario hábil envía los correos que ya tienen turno. SendGuardService aplica la ventana exacta de
 * la Ley 2300, los festivos y el límite diario. Se activa con prospeccion.jobs.send-dispatcher.enabled=true
 * (apagado por defecto).
 */
@Component
@ConditionalOnProperty(name = "prospeccion.jobs.send-dispatcher.enabled", havingValue = "true")
public class SendDispatcherJob {

    private final TenantJobRunner tenantJobRunner;
    private final SendQueueService sendQueueService;

    public SendDispatcherJob(TenantJobRunner tenantJobRunner, SendQueueService sendQueueService) {
        this.tenantJobRunner = tenantJobRunner;
        this.sendQueueService = sendQueueService;
    }

    @Scheduled(cron = "${prospeccion.jobs.send-dispatcher.cron}", zone = "${prospeccion.timezone}")
    public void run() {
        tenantJobRunner.runForEachTenant("send-dispatcher", sendQueueService::dispatchPending);
    }
}
