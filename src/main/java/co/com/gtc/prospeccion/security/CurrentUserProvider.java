package co.com.gtc.prospeccion.security;

import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.multitenancy.TenantContext;

/**
 * Usuario (sc_user.ID) y tenant de la petición actual, tomados del JWT ya validado.
 * El subject (sub) es el LOGIN en el token del login de msa-security y el usuario en el de Sueco Auth
 * (sin confirmar si es el ID o el login): primero se busca como LOGIN y, si no existe y es numérico, como ID.
 */
@Component
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Jwt getCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken();
        }
        throw new AccessDeniedException("No hay un usuario autenticado con JWT");
    }

    /** Valor de sub tal como viene en el token. */
    public String getCurrentSubject() {
        String subject = getCurrentJwt().getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AccessDeniedException("El token no trae sub");
        }
        return subject;
    }

    /** sc_user.ID del usuario de la petición (consulta la base del tenant actual). */
    /** sc_user del usuario de la petición (consulta la base del tenant actual). Sirve para CREATEDBY y similares. */
    public UserET getCurrentUser() {
        String subject = getCurrentSubject();
        return userRepository.findFirstByLogin(subject)
                .or(() -> isNumeric(subject) ? userRepository.findById(Integer.valueOf(subject)) : Optional.empty())
                .orElseThrow(() -> new NotFoundException(MessageKeys.USER_NOT_FOUND, "Usuario del token no está en sc_user"));
    }

    /** sc_user.ID del usuario de la petición. */
    public Integer getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public String getCurrentTenant() {
        return TenantContext.getTenant();
    }

    private static boolean isNumeric(String value) {
        return value.length() <= 9 && value.chars().allMatch(Character::isDigit);
    }
}
