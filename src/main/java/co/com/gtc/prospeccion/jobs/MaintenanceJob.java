package co.com.gtc.prospeccion.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.credits.service.CreditLedgerService;

import co.com.gtc.prospeccion.multitenancy.TenantJobRunner;

/**
 * Diario: vence borradores, purga datos según retención y alerta de créditos.
 * Se activa con prospeccion.jobs.maintenance.enabled=true (apagado por defecto).
 */
@Component
@ConditionalOnProperty(name = "prospeccion.jobs.maintenance.enabled", havingValue = "true")
public class MaintenanceJob {

    private final TenantJobRunner tenantJobRunner;
    private final CreditLedgerService creditLedgerService;

    public MaintenanceJob(TenantJobRunner tenantJobRunner, CreditLedgerService creditLedgerService) {
        this.tenantJobRunner = tenantJobRunner;
        this.creditLedgerService = creditLedgerService;
    }

    @Scheduled(cron = "${prospeccion.jobs.maintenance.cron}", zone = "${prospeccion.timezone}")
    public void run() {
        tenantJobRunner.runForEachTenant("maintenance", () -> {
            // Alerta de créditos por tenant (4.2): deja un WARN por cada proveedor que no esté en OK.
            creditLedgerService.checkLowCreditAlerts();
            // TODO: DraftService.expireOldDrafts() y purgas según retención.
        });
    }
}
