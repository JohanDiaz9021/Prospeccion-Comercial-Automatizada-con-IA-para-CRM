package co.com.gtc.prospeccion.tracking.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Unsubscribe;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;

/**
 * Token = base64url("tenant:sendQueueId") + "." + base64url(HMAC-SHA256(secreto, "unsubscribe:v1:tenant:id")).
 * <ul>
 * <li>El secreto sale de SSM (prospeccion.unsubscribe.token-secret-ssm-parameter) o, solo en local, de
 * prospeccion.unsubscribe.token-secret. Mínimo 32 caracteres.</li>
 * <li>La firma se compara en tiempo constante. Un token dañado nunca lanza error: se rechaza.</li>
 * <li>Cambiar el secreto invalida todos los enlaces ya enviados: no se rota sin un plan.</li>
 * </ul>
 */
@Service
public class UnsubscribeTokenServiceImpl implements UnsubscribeTokenService {

    static final String VERSION = "unsubscribe:v1:";
    /** Píxel de apertura (5.4): otra firma, para que un enlace de baja no sirva de píxel ni al revés. */
    static final String OPEN_VERSION = "open:v1:";
    static final int MIN_SECRET_LENGTH = 32;
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Supplier<String> secret;
    private final String baseUrl;
    private final String openPixelBaseUrl;

    @Autowired
    public UnsubscribeTokenServiceImpl(ProspeccionProperties properties, AwsSsmTenantConfigResolver ssm) {
        this(() -> resolveSecret(properties.getUnsubscribe(), ssm), properties.getUnsubscribe().getBaseUrl(),
                properties.getTracking().getOpenPixel().getBaseUrl());
    }

    UnsubscribeTokenServiceImpl(Supplier<String> secret, String baseUrl) {
        this(secret, baseUrl, null);
    }

    UnsubscribeTokenServiceImpl(Supplier<String> secret, String baseUrl, String openPixelBaseUrl) {
        this.secret = secret;
        this.baseUrl = baseUrl;
        this.openPixelBaseUrl = openPixelBaseUrl;
    }

    @Override
    public String createToken(Integer sendQueueId) {
        return token(VERSION, sendQueueId);
    }

    @Override
    public String createOpenPixelUrl(Integer sendQueueId) {
        if (openPixelBaseUrl == null || openPixelBaseUrl.isBlank()) {
            throw new IllegalStateException("Falta prospeccion.tracking.open-pixel.base-url (OPEN_PIXEL_BASE_URL)");
        }
        return UriComponentsBuilder.fromHttpUrl(openPixelBaseUrl).pathSegment(token(OPEN_VERSION, sendQueueId) + ".gif")
                .build().toUriString();
    }

    @Override
    public Optional<UnsubscribeTarget> verifyOpen(String token) {
        return verify(OPEN_VERSION, token);
    }

    private String token(String version, Integer sendQueueId) {
        String tenant = TenantContext.getTenant();
        if (tenant == null || !TENANT.matcher(tenant).matches()) {
            throw new IllegalStateException("No hay un tenant válido en TenantContext para firmar el enlace de baja");
        }
        if (sendQueueId == null || sendQueueId <= 0) {
            throw new IllegalArgumentException("El envío del enlace de baja no es válido");
        }
        String payload = tenant + ":" + sendQueueId;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(version, payload);
    }

    @Override
    public String createUrl(Integer sendQueueId) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Falta prospeccion.unsubscribe.base-url (UNSUBSCRIBE_BASE_URL)");
        }
        return UriComponentsBuilder.fromHttpUrl(baseUrl).queryParam("t", createToken(sendQueueId)).build().toUriString();
    }

    @Override
    public Optional<UnsubscribeTarget> verify(String token) {
        return verify(VERSION, token);
    }

    private Optional<UnsubscribeTarget> verify(String version, String token) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            String payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] expected = sign(version, payload).getBytes(StandardCharsets.US_ASCII);
            byte[] received = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, received)) {
                return Optional.empty();
            }
            int colon = payload.lastIndexOf(':');
            String tenant = payload.substring(0, colon);
            int id = Integer.parseInt(payload.substring(colon + 1));
            if (!TENANT.matcher(tenant).matches() || id <= 0) {
                return Optional.empty();
            }
            return Optional.of(new UnsubscribeTarget(tenant, id));
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            return Optional.empty(); // base64 inválido, sin ':' o id que no es número
        }
    }

    private String sign(String version, String payload) {
        String key = secret.get();
        if (key == null || key.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("El secreto de los enlaces de baja falta o tiene menos de "
                    + MIN_SECRET_LENGTH + " caracteres (prospeccion.unsubscribe)");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal((version + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar el enlace de baja", e);
        }
    }

    private static String resolveSecret(Unsubscribe settings, AwsSsmTenantConfigResolver ssm) {
        if (settings.getTokenSecret() != null && !settings.getTokenSecret().isBlank()) {
            return settings.getTokenSecret();
        }
        String parameter = settings.getTokenSecretSsmParameter();
        return parameter == null || parameter.isBlank() ? null : ssm.readParameter(parameter);
    }
}
