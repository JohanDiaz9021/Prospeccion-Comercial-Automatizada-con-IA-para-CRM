package co.com.gtc.prospeccion.multitenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.security.SecurityResponseWriter;
import jakarta.servlet.FilterChain;

class TenantFromJwtFilterTest {

    private final TenantFromJwtFilter filter =
            new TenantFromJwtFilter(Set.of("tenant_a")::contains, new SecurityResponseWriter(new ObjectMapper()));

    private final AtomicReference<String> tenantInsideChain = new AtomicReference<>();
    private final AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
    private final FilterChain chain = (request, response) -> {
        chainCalled.set(true);
        tenantInsideChain.set(TenantContext.getTenant());
    };

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void fijaElTenantDelTokenYLoLimpiaAlTerminar() throws Exception {
        authenticate("tenant_a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/config"), response, chain);

        assertThat(tenantInsideChain.get()).isEqualTo("tenant_a");
        assertThat(TenantContext.getTenant()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aceptaElHeaderSiCoincideConElToken() throws Exception {
        authenticate("tenant_a");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config");
        request.addHeader(TenantContext.TENANT_HEADER, "tenant_a");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(tenantInsideChain.get()).isEqualTo("tenant_a");
    }

    @Test
    void rechazaSiElHeaderNoCoincideConElToken() throws Exception {
        authenticate("tenant_a");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config");
        request.addHeader(TenantContext.TENANT_HEADER, "tenant_b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertRejected(response);
    }

    @Test
    void rechazaTenantNoHabilitado() throws Exception {
        authenticate("tenant_b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/config"), response, chain);

        assertRejected(response);
    }

    @Test
    void rechazaTokenSinDbCode() throws Exception {
        authenticate(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/config"), response, chain);

        assertRejected(response);
    }

    @Test
    void enLocalUsaElTenantFijoSiElTokenNoTraeDbCode() throws Exception {
        // Así llegan los tokens del login local del CRM (msa-security-crm en modo local).
        TenantFromJwtFilter local = new TenantFromJwtFilter(Set.of("tenant_a")::contains,
                new SecurityResponseWriter(new ObjectMapper()), "tenant_a");
        authenticate(null);

        local.doFilter(new MockHttpServletRequest("GET", "/api/config"), new MockHttpServletResponse(), chain);

        assertThat(tenantInsideChain.get()).isEqualTo("tenant_a");
    }

    @Test
    void elTenantFijoNoReemplazaElDelTokenNiSaltaLasValidaciones() throws Exception {
        TenantFromJwtFilter local = new TenantFromJwtFilter(Set.of("tenant_a")::contains,
                new SecurityResponseWriter(new ObjectMapper()), "tenant_a");
        authenticate("tenant_b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        local.doFilter(new MockHttpServletRequest("GET", "/api/config"), response, chain);

        assertRejected(response); // tenant_b no está habilitado
    }

    @Test
    void dejaPasarSinTenantLasPeticionesSinJwt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/public/unsubscribe"), response, chain);

        assertThat(chainCalled.get()).isTrue();
        assertThat(tenantInsideChain.get()).isNull();
    }

    private void assertRejected(MockHttpServletResponse response) throws Exception {
        assertThat(chainCalled.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("lblTenantNotAllowed");
    }

    private static void authenticate(String dbCode) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS512").subject("1");
        if (dbCode != null) {
            builder.claim(TenantFromJwtFilter.TENANT_CLAIM, dbCode);
        } else {
            builder.claim("roles", "ADMINISTRADOR");
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }
}
