package co.com.gtc.prospeccion.common.exception;

import lombok.Getter;

/**
 * Falla de un proveedor externo (Apollo, Hunter, Gemini, buzón) o de otro microservicio.
 */
@Getter
public class ExternalServiceException extends RuntimeException {

    private final String provider;

    public ExternalServiceException(String provider, String detail, Throwable cause) {
        super(detail, cause);
        this.provider = provider;
    }
}
