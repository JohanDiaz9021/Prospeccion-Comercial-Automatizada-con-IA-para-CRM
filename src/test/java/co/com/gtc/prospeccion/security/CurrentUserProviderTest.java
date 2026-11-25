package co.com.gtc.prospeccion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.UserRepository;

class CurrentUserProviderTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CurrentUserProvider provider = new CurrentUserProvider(userRepository);

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void subConElLoginDevuelveElIdDeScUser() {
        authenticate("jdiaz");
        when(userRepository.findFirstByLogin("jdiaz")).thenReturn(Optional.of(user(42)));

        assertThat(provider.getCurrentUserId()).isEqualTo(42);
    }

    @Test
    void subNumericoQueNoEsLoginSeTomaComoId() {
        authenticate("7");
        when(userRepository.findFirstByLogin("7")).thenReturn(Optional.empty());
        when(userRepository.findById(7)).thenReturn(Optional.of(user(7)));

        assertThat(provider.getCurrentUserId()).isEqualTo(7);
    }

    @Test
    void usuarioQueNoExisteEnScUser() {
        authenticate("nadie");
        when(userRepository.findFirstByLogin("nadie")).thenReturn(Optional.empty());

        assertThatThrownBy(provider::getCurrentUserId).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sinTokenNoHayUsuario() {
        assertThatThrownBy(provider::getCurrentUserId).isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS512").subject(subject).claim("db_code", "t").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** UserET es de solo lectura (sin setters): el ID se pone por reflexión. */
    private static UserET user(int id) {
        UserET user = new UserET();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
