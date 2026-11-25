package co.com.gtc.prospeccion.credits.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Consumo de créditos de un proveedor en el mes actual, para el tenant (tarea 4.2). Lo muestra el panel (6.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditSummaryResponseDTO {

    public enum Status {
        /** Por debajo del porcentaje de alerta. */
        OK,
        /** Llegó al porcentaje de alerta del presupuesto mensual, o el saldo del proveedor no alcanza una campaña. */
        WARNING,
        /** Se acabó el presupuesto mensual: no se gastan más créditos este mes. */
        EXCEEDED
    }

    private String provider;
    /** Mes calendario en hora de Colombia, por ejemplo 2026-11. */
    private String period;
    /** Créditos gastados en el mes (este tenant). */
    private BigDecimal credits;
    /** Presupuesto mensual configurado; null = sin tope. */
    private BigDecimal monthlyBudget;
    /** Porcentaje del presupuesto usado; null si no hay tope. */
    private Integer percentUsed;
    /** Tope por campaña; null = sin tope. */
    private BigDecimal perSearchRunLimit;
    /** Costo estimado en USD (si se configuró el precio por crédito). */
    private BigDecimal costUsd;
    /** Saldo que informa el proveedor para TODA la cuenta (solo Hunter lo expone); null si no se pudo consultar. */
    private BigDecimal providerRemaining;
    /** Por qué no hay saldo del proveedor (sin llave, rechazo, sin red o no lo informa); null si se leyó bien. */
    private String providerNote;
    private Status status;
}
