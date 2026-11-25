package co.com.gtc.prospeccion.credits.service;

import java.math.BigDecimal;
import java.util.List;

import co.com.gtc.prospeccion.credits.CreditCharge;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.credits.dto.CreditSummaryResponseDTO;

/**
 * Control de créditos de Apollo y Hunter por tenant (tarea 4.2, docs/negocio/creditos.md).
 * Antes de cada llamada que cuesta: {@link #ensureAvailable}. Después: {@link #record} con lo que de verdad se gastó.
 */
public interface CreditLedgerService {

    /**
     * Comprueba que el gasto cabe en el tope de la campaña y en el presupuesto mensual del tenant.
     *
     * @throws CreditLimitExceededException si no cabe (no se llama al proveedor)
     */
    void ensureAvailable(String provider, Integer searchRunId, BigDecimal credits);

    /** Anota un gasto en la_creditledger (los gastos en 0 no se anotan). */
    void record(CreditCharge charge);

    /** Consumo del mes por proveedor, con presupuesto, porcentaje y estado. */
    List<CreditSummaryResponseDTO> getSummary();

    /** Llamado por MaintenanceJob para cada tenant: registra una alerta por cada proveedor que no esté en OK. */
    List<CreditSummaryResponseDTO> checkLowCreditAlerts();
}
