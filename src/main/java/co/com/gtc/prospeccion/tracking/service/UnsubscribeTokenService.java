package co.com.gtc.prospeccion.tracking.service;

import java.util.Optional;

/**
 * Token firmado del enlace de baja (5.5). Va en cada correo (enlace del pie y encabezado List-Unsubscribe) y no
 * vence: quien recibió un correo debe poder darse de baja siempre. Lleva el tenant porque la página de baja es
 * pública (sin JWT) y debe saber en qué base buscar el envío.
 */
public interface UnsubscribeTokenService {

    /** A quién apunta un token válido. */
    record UnsubscribeTarget(String tenant, Integer sendQueueId) {
    }

    /** Token para un envío (la_sendqueue.ID) del tenant actual (TenantContext). */
    String createToken(Integer sendQueueId);

    /** Enlace completo: prospeccion.unsubscribe.base-url?t=token. */
    String createUrl(Integer sendQueueId);

    /** El destino si la firma es válida; vacío si el token está dañado, alterado o es de otro sistema. */
    Optional<UnsubscribeTarget> verify(String token);

    /**
     * Tarea 5.4: URL del píxel de apertura del envío (prospeccion.tracking.open-pixel.base-url + token + ".gif"). Lleva
     * otra firma que el enlace de baja: uno no sirve por el otro.
     */
    String createOpenPixelUrl(Integer sendQueueId);

    /** Tenant y envío de un token de píxel, o vacío si no es válido. */
    Optional<UnsubscribeTarget> verifyOpen(String token);
}
