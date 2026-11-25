package co.com.gtc.prospeccion.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe las respuestas 401 y 403 de la cadena de seguridad con el mismo GeneralReponse del resto del servicio
 * (los filtros corren antes de los controllers, así que GlobalExceptionHandler no las ve).
 */
@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String messageKey) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), GeneralReponse.error(messageKey, null));
    }

    /** Sin token, o con un token mal firmado o vencido. */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, HttpStatus.UNAUTHORIZED, MessageKeys.UNAUTHORIZED);
        };
    }

    /** Token válido sin permiso para la operación. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(response, HttpStatus.FORBIDDEN, MessageKeys.ACCESS_DENIED);
    }
}
