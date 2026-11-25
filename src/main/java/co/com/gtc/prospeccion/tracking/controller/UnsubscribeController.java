package co.com.gtc.prospeccion.tracking.controller;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService.UnsubscribeTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Baja pública (5.5), sin JWT: la protege el token firmado del parámetro t, que también dice el tenant.
 * <ul>
 * <li>GET: página con un solo botón "Darme de Baja". No da de baja por sí solo, porque los antivirus de correo
 * abren los enlaces automáticamente.</li>
 * <li>POST: hace la baja. Lo usa el botón de la página y la baja en un clic de Gmail y Outlook
 * (List-Unsubscribe-Post: List-Unsubscribe=One-Click, RFC 8058).</li>
 * </ul>
 * Responde HTML. Un token inválido o de un tenant no habilitado da la misma página de "enlace no válido".
 */
@RestController
@RequestMapping(value = "/public/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
@Tag(name = "Baja (público)")
public class UnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeController.class);

    private final UnsubscribeTokenService tokenService;
    private final UnsubscribeService unsubscribeService;
    private final ProspeccionProperties properties;

    public UnsubscribeController(UnsubscribeTokenService tokenService, UnsubscribeService unsubscribeService,
            ProspeccionProperties properties) {
        this.tokenService = tokenService;
        this.unsubscribeService = unsubscribeService;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "Página de confirmación de la baja (no da de baja por sí sola)")
    public ResponseEntity<String> confirm(@RequestParam(value = "t", required = false) String token) {
        return handle(token, target -> unsubscribeService.recipientOf(target.sendQueueId())
                .map(r -> r.alreadyUnsubscribed()
                        ? UnsubscribePages.already(r.maskedEmail())
                        : UnsubscribePages.confirm(r.maskedEmail(), token)));
    }

    @PostMapping
    @Operation(summary = "Da de baja el correo del envío (botón de la página o baja en un clic del cliente de correo)")
    public ResponseEntity<String> unsubscribe(@RequestParam(value = "t", required = false) String token) {
        return handle(token, target -> unsubscribeService.unsubscribe(target.sendQueueId())
                .map(r -> UnsubscribePages.done(r.maskedEmail())));
    }

    private ResponseEntity<String> handle(String token, Function<UnsubscribeTarget, Optional<String>> action) {
        Optional<UnsubscribeTarget> target;
        try {
            target = tokenService.verify(token);
        } catch (IllegalStateException e) {
            log.error("Baja: no se pudo verificar el enlace: {}", e.getMessage());
            return html(HttpStatus.SERVICE_UNAVAILABLE, UnsubscribePages.unavailable());
        }
        if (target.isEmpty() || !properties.isTenantEnabled(target.get().tenant())) {
            return html(HttpStatus.BAD_REQUEST, UnsubscribePages.invalid());
        }
        try {
            Optional<String> page = inTenant(target.get().tenant(), () -> action.apply(target.get()));
            return page.map(p -> html(HttpStatus.OK, p))
                    .orElseGet(() -> html(HttpStatus.NOT_FOUND, UnsubscribePages.invalid()));
        } catch (RuntimeException e) {
            log.error("Baja del envío {} falló", target.get().sendQueueId(), e);
            return html(HttpStatus.SERVICE_UNAVAILABLE, UnsubscribePages.unavailable());
        }
    }

    /** La ruta es pública: el tenant sale del token y solo vive mientras dura la acción. */
    private static <T> T inTenant(String tenant, Supplier<T> action) {
        String previous = TenantContext.getTenant();
        TenantContext.setTenant(tenant);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenant(previous);
            }
        }
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").body(body);
    }
}
