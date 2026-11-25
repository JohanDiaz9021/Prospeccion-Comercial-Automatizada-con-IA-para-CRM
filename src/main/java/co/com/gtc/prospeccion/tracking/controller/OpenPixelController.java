package co.com.gtc.prospeccion.tracking.controller;

import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.TrackingService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService.UnsubscribeTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Píxel de apertura (5.4), público y sin JWT: la imagen de 1x1 que lleva el correo. Registra la primera apertura del
 * envío como dato APROXIMADO: Apple y algunos antivirus abren solos los correos y otros bloquean las imágenes.
 * <p>
 * Siempre responde la imagen, pase lo que pase: quien abre el correo nunca ve un error. Solo registra si
 * prospeccion.tracking.open-pixel.enabled es true (apagado por defecto) y el token es válido.
 */
@RestController
@RequestMapping("/public/open")
@Tag(name = "Baja (público)")
public class OpenPixelController {

    private static final Logger log = LoggerFactory.getLogger(OpenPixelController.class);

    /** GIF transparente de 1x1. */
    static final byte[] PIXEL = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final UnsubscribeTokenService tokenService;
    private final TrackingService trackingService;
    private final ProspeccionProperties properties;

    public OpenPixelController(UnsubscribeTokenService tokenService, TrackingService trackingService,
            ProspeccionProperties properties) {
        this.tokenService = tokenService;
        this.trackingService = trackingService;
        this.properties = properties;
    }

    @GetMapping("/{token}")
    @Operation(summary = "Píxel de apertura (dato aproximado; apagado por defecto)")
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        if (properties.getTracking().getOpenPixel().isEnabled()) {
            register(token.endsWith(".gif") ? token.substring(0, token.length() - 4) : token);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_GIF)
                .header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                .header("Referrer-Policy", "no-referrer").body(PIXEL);
    }

    private void register(String token) {
        try {
            Optional<UnsubscribeTarget> target = tokenService.verifyOpen(token);
            if (target.isEmpty() || !properties.isTenantEnabled(target.get().tenant())) {
                return;
            }
            String previous = TenantContext.getTenant();
            TenantContext.setTenant(target.get().tenant());
            try {
                trackingService.registerOpen(target.get().sendQueueId());
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenant(previous);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Píxel de apertura: no se pudo registrar ({})", e.getClass().getSimpleName());
        }
    }
}
