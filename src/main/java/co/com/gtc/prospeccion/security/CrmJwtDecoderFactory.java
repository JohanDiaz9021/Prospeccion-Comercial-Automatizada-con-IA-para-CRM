package co.com.gtc.prospeccion.security;

import java.util.Base64;
import java.util.Set;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Valida el JWT del CRM igual que msa-apigateway-crm (JwtTokenParser): firma HMAC con la clave
 * JWT_SECRET_KEY en Base64. Acepta HS256 (login local de msa-security) y HS512 (token de Sueco Auth).
 * Las fechas (exp, nbf) las valida Spring con JwtValidators.createDefault().
 */
public final class CrmJwtDecoderFactory {

    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.HS256, JWSAlgorithm.HS384, JWSAlgorithm.HS512);

    /** HMAC-SHA256 exige una clave de al menos 256 bits. */
    private static final int MIN_KEY_BYTES = 32;

    private CrmJwtDecoderFactory() {
    }

    public static JwtDecoder create(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "Falta prospeccion.security.jwt-secret (variable JWT_SECRET_KEY, la misma del gateway).");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET_KEY no es un texto Base64 válido.");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET_KEY debe tener al menos 256 bits (32 bytes).");
        }

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, new ImmutableSecret<>(key)));
        // Las fechas las valida Spring (JwtValidators); Nimbus solo verifica la firma.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }
}
