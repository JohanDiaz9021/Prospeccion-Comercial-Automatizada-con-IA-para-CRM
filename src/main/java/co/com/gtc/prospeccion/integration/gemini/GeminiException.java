package co.com.gtc.prospeccion.integration.gemini;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;

/**
 * Errores del cliente de Gemini. Al heredar de ExternalServiceException, GlobalExceptionHandler responde 502
 * con lblExternalServiceError. Los mensajes nunca incluyen la llave ni el prompt.
 */
public class GeminiException extends ExternalServiceException {

    public static final String PROVIDER = "gemini";

    public GeminiException(String detail) {
        super(PROVIDER, detail, null);
    }

    public GeminiException(String detail, Throwable cause) {
        super(PROVIDER, detail, cause);
    }

    /** Falta configuración (por ejemplo, la llave). */
    public static class Configuration extends GeminiException {
        public Configuration(String detail) {
            super(detail);
        }
    }

    /** Google rechazó la petición (4xx distinto de 429): reintentar no lo arregla. */
    public static class Rejected extends GeminiException {
        private final int status;

        public Rejected(String detail, int status) {
            super(detail);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** Se agotaron los reintentos por 429, 5xx o fallas de red. */
    public static class RetriesExhausted extends GeminiException {
        public RetriesExhausted(String detail) {
            super(detail);
        }
    }

    /** Gemini se negó a responder por sus filtros de seguridad. */
    public static class Blocked extends GeminiException {
        public Blocked(String detail) {
            super(detail);
        }
    }

    /** La respuesta llegó vacía o con un formato inesperado. */
    public static class InvalidResponse extends GeminiException {
        public InvalidResponse(String detail) {
            super(detail);
        }

        public InvalidResponse(String detail, Throwable cause) {
            super(detail, cause);
        }
    }
}
