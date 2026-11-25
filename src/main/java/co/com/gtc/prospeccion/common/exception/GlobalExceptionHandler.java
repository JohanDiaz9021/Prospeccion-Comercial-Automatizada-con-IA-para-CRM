package co.com.gtc.prospeccion.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.multitenancy.TenantNotAllowedException;

/**
 * Convierte las excepciones en GeneralReponse con claves lbl* (las claves están en MessageKeys).
 * Hereda de ResponseEntityExceptionHandler para que los errores propios de Spring MVC conserven su estado
 * (400 JSON mal formado o parámetro faltante, 404 ruta inexistente, 405 método no permitido, 415...)
 * en lugar de terminar en 500. Los 401 y 403 de la cadena de seguridad los escribe SecurityResponseWriter.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<GeneralReponse> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GeneralReponse.error(e.getMessageKey(), null));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<GeneralReponse> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(GeneralReponse.error(e.getMessageKey(), null));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<GeneralReponse> handleBusiness(BusinessException e) {
        return ResponseEntity.badRequest().body(GeneralReponse.error(e.getMessageKey(), null));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<GeneralReponse> handleExternal(ExternalServiceException e) {
        log.warn("Falla del proveedor externo {}", e.getProvider());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(GeneralReponse.error(MessageKeys.EXTERNAL_SERVICE_ERROR, null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<GeneralReponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(GeneralReponse.error(MessageKeys.ACCESS_DENIED, null));
    }

    @ExceptionHandler(TenantNotAllowedException.class)
    public ResponseEntity<GeneralReponse> handleTenantNotAllowed(TenantNotAllowedException e) {
        log.warn(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(GeneralReponse.error(MessageKeys.TENANT_NOT_ALLOWED, null));
    }

    /** Mientras el módulo sea un esqueleto, los métodos sin implementar responden 501. */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<GeneralReponse> handleNotImplemented(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(GeneralReponse.error(MessageKeys.NOT_IMPLEMENTED, "TODO"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GeneralReponse> handleUnexpected(Exception e) {
        // No registrar cuerpos de petición: pueden traer correos u otros datos personales.
        log.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GeneralReponse.error(MessageKeys.UNEXPECTED_ERROR, null));
    }

    /** Errores de Spring MVC: se conserva el estado HTTP y sus headers (por ejemplo Allow en un 405). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        String messageKey;
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            messageKey = MessageKeys.NOT_FOUND;
        } else if (statusCode.is4xxClientError()) {
            messageKey = MessageKeys.VALIDATION_ERROR;
        } else {
            log.error("Error de Spring MVC", ex);
            messageKey = MessageKeys.UNEXPECTED_ERROR;
        }
        return ResponseEntity.status(statusCode).headers(headers).body(GeneralReponse.error(messageKey, null));
    }
}
