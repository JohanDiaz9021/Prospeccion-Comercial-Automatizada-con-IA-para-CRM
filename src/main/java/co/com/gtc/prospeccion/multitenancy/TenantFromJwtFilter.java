package co.com.gtc.prospeccion.multitenancy;

import java.io.IOException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.security.SecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fija el tenant de la petición a partir del claim db_code del JWT ya validado (BearerTokenAuthenticationFilter).
 * A diferencia de msa-params y msa-cotizaciones, el header X-Tenant-Database-Code nunca se usa solo:
 * si viene, debe coincidir con el claim. Responde 403 si falta el claim, si el header no coincide o si el
 * módulo no está habilitado para el tenant. Las peticiones sin JWT (/public/**, health) pasan sin tenant.
 * Se registra en SecurityConfig, después de BearerTokenAuthenticationFilter.
 */
public class TenantFromJwtFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "db_code";

    private static final Logger log = LoggerFactory.getLogger(TenantFromJwtFilter.class);

    private final Predicate<String> tenantEnabled;
    private final SecurityResponseWriter responseWriter;
    /** Solo en el perfil local (prospeccion.local-tenant): tenant para tokens sin db_code. */
    private final String fixedTenant;

    public TenantFromJwtFilter(Predicate<String> tenantEnabled, SecurityResponseWriter responseWriter) {
        this(tenantEnabled, responseWriter, null);
    }

    /**
     * @param fixedTenant tenant para los tokens sin db_code, o null para exigir el claim. El login local del CRM
     *                    (msa-security-crm con SECURITY_AUTH_MODE=local) emite tokens sin db_code a propósito y
     *                    el gateway local no agrega el header; en qa/prod siempre es null.
     */
    public TenantFromJwtFilter(Predicate<String> tenantEnabled, SecurityResponseWriter responseWriter,
            String fixedTenant) {
        this.tenantEnabled = tenantEnabled;
        this.responseWriter = responseWriter;
        this.fixedTenant = StringUtils.hasText(fixedTenant) ? fixedTenant : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }

        String tenant = jwtAuthentication.getToken().getClaimAsString(TENANT_CLAIM);
        if (!StringUtils.hasText(tenant) && fixedTenant != null) {
            tenant = fixedTenant;
        }
        String headerTenant = request.getHeader(TenantContext.TENANT_HEADER);
        String rejection = null;
        if (!StringUtils.hasText(tenant)) {
            rejection = "el token no trae " + TENANT_CLAIM;
        } else if (StringUtils.hasText(headerTenant) && !headerTenant.equals(tenant)) {
            rejection = "el header " + TenantContext.TENANT_HEADER + " no coincide con el token";
        } else if (!tenantEnabled.test(tenant)) {
            rejection = "el módulo no está habilitado para el tenant " + tenant;
        }
        if (rejection != null) {
            log.warn("Petición rechazada ({} {}): {}", request.getMethod(), request.getRequestURI(), rejection);
            responseWriter.write(response, HttpStatus.FORBIDDEN, MessageKeys.TENANT_NOT_ALLOWED);
            return;
        }

        try {
            TenantContext.setTenant(tenant);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
