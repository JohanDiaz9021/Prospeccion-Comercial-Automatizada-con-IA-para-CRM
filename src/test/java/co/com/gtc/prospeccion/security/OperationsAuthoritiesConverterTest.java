package co.com.gtc.prospeccion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class OperationsAuthoritiesConverterTest {

    private final OperationsAuthoritiesConverter converter = new OperationsAuthoritiesConverter(Map.of(
            "ADMINISTRADOR", List.of("VER", "EJECUTAR", "APROBAR", "CONFIGURAR"),
            "comercial", List.of("aprobar"),
            "ROLE_ANALISTA", List.of("VER")));

    private static Jwt token(List<String> roles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "HS512").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            b.claim("roles", roles);
        }
        return b.build();
    }

    private Collection<String> authorities(List<String> roles) {
        return converter.convert(token(roles)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void cadaRolRecibeSusOperacionesYSuRol() {
        assertThat(authorities(List.of("ADMINISTRADOR")))
                .containsExactlyInAnyOrder("ROLE_ADMINISTRADOR", "OP_VER", "OP_EJECUTAR", "OP_APROBAR", "OP_CONFIGURAR");
    }

    @Test
    void cualquierOperacionIncluyeVer() {
        assertThat(authorities(List.of("COMERCIAL"))).containsExactlyInAnyOrder("ROLE_COMERCIAL", "OP_APROBAR", "OP_VER");
    }

    @Test
    void noDuplicaElPrefijoNiImportanLasMayusculas() {
        assertThat(authorities(List.of("ROLE_comercial"))).contains("ROLE_COMERCIAL", "OP_APROBAR")
                .doesNotContain("ROLE_ROLE_COMERCIAL");
        assertThat(authorities(List.of("analista"))).containsExactlyInAnyOrder("ROLE_ANALISTA", "OP_VER");
    }

    @Test
    void superadminTieneTodasComoEnElFront() {
        assertThat(authorities(List.of("ROLE_SUPERADMIN")))
                .contains("ROLE_SUPERADMIN", "OP_VER", "OP_EJECUTAR", "OP_APROBAR", "OP_CONFIGURAR");
    }

    @Test
    void unRolQueNoEstaEnLaTablaNoTieneOperaciones() {
        assertThat(authorities(List.of("CONTADOR"))).containsExactly("ROLE_CONTADOR");
        assertThat(authorities(null)).isEmpty();
        assertThat(authorities(List.of(" ", "CONTADOR", "COMERCIAL")))
                .containsExactlyInAnyOrder("ROLE_CONTADOR", "ROLE_COMERCIAL", "OP_APROBAR", "OP_VER");
    }

    @Test
    void unaOperacionMalEscritaDetieneElArranque() {
        assertThatThrownBy(() -> new OperationsAuthoritiesConverter(Map.of("COMERCIAL", List.of("APROVAR"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APROVAR");
    }
}
