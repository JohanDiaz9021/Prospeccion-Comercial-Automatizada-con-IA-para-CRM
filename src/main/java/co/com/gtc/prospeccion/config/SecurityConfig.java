package co.com.gtc.prospeccion.config;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.multitenancy.TenantFromJwtFilter;
import co.com.gtc.prospeccion.security.CrmJwtDecoderFactory;
import co.com.gtc.prospeccion.security.OperationsAuthoritiesConverter;
import co.com.gtc.prospeccion.security.SecurityResponseWriter;

/**
 * Resource server JWT. Todo exige token salvo /public/** (baja de correos, protegida con token firmado),
 * health y Swagger. El token se valida aquí aunque ya lo haya validado el gateway: el usuario y el tenant
 * salen siempre del token, nunca de ?userName= ni de un header suelto.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(ProspeccionProperties properties) {
        return CrmJwtDecoderFactory.create(properties.getSecurity().getJwtSecret());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ProspeccionProperties properties,
            SecurityResponseWriter responseWriter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // TODO: decidir si Swagger queda abierto en qa (en prod springdoc está apagado).
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(
                                properties.getSecurity().getOperationsByRole())))
                        .authenticationEntryPoint(responseWriter.authenticationEntryPoint())
                        .accessDeniedHandler(responseWriter.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(responseWriter.authenticationEntryPoint())
                        .accessDeniedHandler(responseWriter.accessDeniedHandler()))
                // Se instancia aquí (no como @Component) para que no quede registrado dos veces como filtro.
                // En local, el login del CRM no pone db_code en el token: se usa el tenant fijo de local-tenant.
                .addFilterAfter(new TenantFromJwtFilter(properties::isTenantEnabled, responseWriter,
                        properties.getLocalTenant().isEnabled() ? properties.getLocalTenant().getCode() : null),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Tarea 2.9: los roles del claim "roles" quedan como ROLE_<rol> y sus operaciones del módulo como OP_<operación>
     * (ver {@link OperationsAuthoritiesConverter}). Cada endpoint exige la suya con @PreAuthorize.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter(Map<String, List<String>> operationsByRole) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new OperationsAuthoritiesConverter(operationsByRole));
        return converter;
    }
}
