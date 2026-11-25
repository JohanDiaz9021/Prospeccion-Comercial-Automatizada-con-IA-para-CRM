package co.com.gtc.prospeccion.integration.hunter.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Account Information (GET /v2/account, gratis), contenido de "data". Saldo de TODA la cuenta de Hunter.
 * requests.credits solo viene cuando el equipo tiene una bolsa única de créditos; si no, vienen searches y
 * verifications por separado.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HunterAccountResponse {

    private String planName;
    private String resetDate;
    private Requests requests;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Requests {
        private Usage credits;
        private Usage searches;
        private Usage verifications;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private BigDecimal used;
        /** Cupo del periodo según el plan (no lo que queda). */
        private BigDecimal available;
        private BigDecimal remaining;

        /** Lo que queda: remaining si Hunter lo envía; si no, available − used (nunca negativo). */
        public BigDecimal left() {
            if (remaining != null) {
                return remaining;
            }
            if (available == null) {
                return null;
            }
            BigDecimal resto = available.subtract(used == null ? BigDecimal.ZERO : used);
            return resto.signum() < 0 ? BigDecimal.ZERO : resto;
        }
    }

    /**
     * Créditos que quedan: la bolsa única si existe; si no, búsquedas + verificaciones convertidas a créditos
     * (1 búsqueda = 1 crédito, 1 verificación = 0,5 crédito). null si Hunter no lo informa.
     */
    public BigDecimal remainingCredits() {
        if (requests == null) {
            return null;
        }
        if (requests.credits != null && requests.credits.left() != null) {
            return requests.credits.left();
        }
        BigDecimal searches = requests.searches == null ? null : requests.searches.left();
        BigDecimal verifications = requests.verifications == null ? null : requests.verifications.left();
        if (searches == null && verifications == null) {
            return null;
        }
        BigDecimal total = searches == null ? BigDecimal.ZERO : searches;
        return verifications == null ? total : total.add(verifications.multiply(new BigDecimal("0.5")));
    }
}
