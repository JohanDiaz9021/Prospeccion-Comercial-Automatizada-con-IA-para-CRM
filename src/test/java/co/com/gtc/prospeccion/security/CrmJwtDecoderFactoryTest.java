package co.com.gtc.prospeccion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class CrmJwtDecoderFactoryTest {

    private static final byte[] KEY = randomKey(64);
    private static final String SECRET = Base64.getEncoder().encodeToString(KEY);

    @Test
    void aceptaTokenHs512DeSuecoAuth() throws Exception {
        Jwt jwt = CrmJwtDecoderFactory.create(SECRET).decode(token(JWSAlgorithm.HS512, KEY, 60));

        assertThat(jwt.getSubject()).isEqualTo("1");
        assertThat(jwt.getClaimAsString("db_code")).isEqualTo("tenant_a");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMINISTRADOR");
    }

    @Test
    void aceptaTokenHs256DelLoginLocal() throws Exception {
        Jwt jwt = CrmJwtDecoderFactory.create(SECRET).decode(token(JWSAlgorithm.HS256, KEY, 60));

        assertThat(jwt.getSubject()).isEqualTo("1");
    }

    @Test
    void rechazaTokenFirmadoConOtraClave() throws Exception {
        JwtDecoder decoder = CrmJwtDecoderFactory.create(SECRET);
        String ajeno = token(JWSAlgorithm.HS512, randomKey(64), 60);

        assertThatThrownBy(() -> decoder.decode(ajeno)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaTokenVencido() throws Exception {
        JwtDecoder decoder = CrmJwtDecoderFactory.create(SECRET);
        String vencido = token(JWSAlgorithm.HS512, KEY, -10);

        assertThatThrownBy(() -> decoder.decode(vencido)).isInstanceOf(JwtException.class);
    }

    @Test
    void fallaAlArrancarSiLaClaveFaltaOEsInvalida() {
        assertThatThrownBy(() -> CrmJwtDecoderFactory.create(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CrmJwtDecoderFactory.create("no-es-base64!")).isInstanceOf(IllegalStateException.class);
        String corta = Base64.getEncoder().encodeToString(randomKey(16));
        assertThatThrownBy(() -> CrmJwtDecoderFactory.create(corta)).isInstanceOf(IllegalStateException.class);
    }

    private static String token(JWSAlgorithm algorithm, byte[] key, int minutes) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .claim("db_code", "tenant_a")
                .claim("roles", List.of("ADMINISTRADOR"))
                .issueTime(new Date(now))
                .expirationTime(new Date(now + minutes * 60_000L))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    private static byte[] randomKey(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
