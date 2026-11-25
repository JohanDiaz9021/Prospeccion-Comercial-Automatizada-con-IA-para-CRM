package co.com.gtc.prospeccion.integration.apollo;

import java.time.Duration;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;

/**
 * Errores del cliente de Apollo. Al heredar de ExternalServiceException, GlobalExceptionHandler responde 502
 * con lblExternalServiceError. Los mensajes nunca incluyen la llave ni datos de personas.
 */
public class ApolloException extends ExternalServiceException {

    public static final String PROVIDER = "apollo";

    public ApolloException(String detail) {
        super(PROVIDER, detail, null);
    }

    public ApolloException(String detail, Throwable cause) {
        super(PROVIDER, detail, cause);
    }

    /** Falta la llave. */
    public static class Configuration extends ApolloException {
        public Configuration(String detail) {
            super(detail);
        }
    }

    /** Apollo rechazó la petición (401 llave inválida, 403 llave sin acceso al endpoint, 422 filtros inválidos). */
    public static class Rejected extends ApolloException {
        private final int status;

        public Rejected(String detail, int status) {
            super(detail);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * Se agotó el cupo de una ventana larga (hora o día): esperar bloquearía el hilo demasiado tiempo.
     * Quien llama (por ejemplo el job de búsquedas) reprograma después de retryAfter.
     */
    public static class RateLimited extends ApolloException {
        private final Duration retryAfter;

        public RateLimited(String detail, Duration retryAfter) {
            super(detail);
            this.retryAfter = retryAfter;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }
    }

    /** Se agotaron los reintentos por 429 de ventana corta, 5xx o fallas de red. */
    public static class RetriesExhausted extends ApolloException {
        public RetriesExhausted(String detail) {
            super(detail);
        }
    }

    /** La respuesta no tiene el formato esperado. */
    public static class InvalidResponse extends ApolloException {
        public InvalidResponse(String detail, Throwable cause) {
            super(detail, cause);
        }
    }
}
