package co.com.gtc.prospeccion.common.exception;

/**
 * Otra persona cambió el mismo dato mientras se editaba (GlobalExceptionHandler responde 409).
 */
public class ConflictException extends BusinessException {

    public ConflictException(String messageKey, String detail) {
        super(messageKey, detail);
    }
}
