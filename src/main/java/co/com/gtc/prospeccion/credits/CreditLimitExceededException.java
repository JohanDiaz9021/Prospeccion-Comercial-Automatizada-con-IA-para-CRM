package co.com.gtc.prospeccion.credits;

import java.math.BigDecimal;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;

/**
 * Se lanza ANTES de llamar al proveedor cuando el gasto superaría un tope: no se gasta nada. La campaña se detiene
 * (o espera al mes siguiente, si el tope es mensual) y los contactos pendientes se retoman después.
 */
public class CreditLimitExceededException extends BusinessException {

    public enum Scope { SEARCH_RUN, MONTHLY }

    private final String provider;
    private final Scope scope;
    private final BigDecimal used;
    private final BigDecimal limit;

    public CreditLimitExceededException(String provider, Scope scope, BigDecimal used, BigDecimal limit) {
        super(MessageKeys.CREDIT_LIMIT, "Tope %s de %s: usados %s de %s".formatted(
                scope == Scope.MONTHLY ? "mensual" : "por campaña", provider, used.stripTrailingZeros().toPlainString(),
                limit.stripTrailingZeros().toPlainString()));
        this.provider = provider;
        this.scope = scope;
        this.used = used;
        this.limit = limit;
    }

    public String getProvider() {
        return provider;
    }

    public Scope getScope() {
        return scope;
    }

    public BigDecimal getUsed() {
        return used;
    }

    public BigDecimal getLimit() {
        return limit;
    }
}
