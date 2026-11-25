package co.com.gtc.prospeccion.common.exception;

import lombok.Getter;

/**
 * Error de negocio con clave lbl* para el frontend.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String messageKey;

    public BusinessException(String messageKey, String detail) {
        super(detail);
        this.messageKey = messageKey;
    }
}
