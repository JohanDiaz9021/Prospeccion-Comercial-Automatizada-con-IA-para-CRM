package co.com.gtc.prospeccion.integration.hunter;

import co.com.gtc.prospeccion.common.exception.ExternalServiceException;

/**
 * Errores del cliente de Hunter. Al heredar de ExternalServiceException, GlobalExceptionHandler responde 502
 * con lblExternalServiceError. Los mensajes nunca incluyen la llave ni correos.
 */
public class HunterException extends ExternalServiceException {

    public static final String PROVIDER = "hunter";

    public HunterException(String detail) {
        super(PROVIDER, detail, null);
    }

    public HunterException(String detail, Throwable cause) {
        super(PROVIDER, detail, cause);
    }

    /** Falta la llave. */
    public static class Configuration extends HunterException {
        public Configuration(String detail) {
            super(detail);
        }
    }

    /** Hunter rechazó la petición (400 parámetros, 401 llave inválida, 422). */
    public static class Rejected extends HunterException {
        private final int status;

        public Rejected(String detail, int status) {
            super(detail);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 429: se acabó el cupo del plan (búsquedas o verificaciones del mes). Reintentar no sirve hasta reset_date. */
    public static class QuotaExhausted extends HunterException {
        public QuotaExhausted(String detail) {
            super(detail);
        }
    }

    /**
     * 451: la persona pidió a Hunter que no procese sus datos. No es una falla: ese correo o persona no se debe
     * contactar (va a la lista de supresión, tarea 5.5).
     */
    public static class OptedOut extends HunterException {
        public OptedOut(String detail) {
            super(detail);
        }
    }

    /** Se agotaron los reintentos por 403 (límite por segundo), 5xx, fallas de red o verificación en curso (202). */
    public static class RetriesExhausted extends HunterException {
        public RetriesExhausted(String detail) {
            super(detail);
        }
    }

    /** La respuesta no tiene el formato esperado. */
    public static class InvalidResponse extends HunterException {
        public InvalidResponse(String detail, Throwable cause) {
            super(detail, cause);
        }
    }
}
