package co.com.gtc.prospeccion.common.exception;

public class NotFoundException extends BusinessException {

    public NotFoundException(String messageKey, String detail) {
        super(messageKey, detail);
    }
}
