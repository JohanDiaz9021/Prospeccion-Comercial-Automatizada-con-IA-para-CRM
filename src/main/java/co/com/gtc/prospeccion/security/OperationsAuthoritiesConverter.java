package co.com.gtc.prospeccion.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Tarea 2.9: authorities del usuario a partir del claim {@code roles} del JWT.
 * <ul>
 * <li>Cada rol queda como {@code ROLE_<ROL>} (sin duplicar el prefijo si el token ya lo trae, como
 * {@code ROLE_SUPERADMIN} de sueco-auth).</li>
 * <li>Sus operaciones salen de {@code prospeccion.security.operations-by-role} y quedan como {@code OP_<OPERACIÓN>}.
 * {@code SUPERADMIN} tiene todas, igual que en el front.</li>
 * <li>Cualquier operación incluye VER: quien aprueba o configura también necesita ver.</li>
 * <li>Un rol que no está en la tabla no tiene operaciones: se niega por defecto.</li>
 * </ul>
 * Cuando la 1.13 entregue el assignmentId del menú, las operaciones pueden salir del permission-service (acciones
 * Abrir, Crear, Editar) sin cambiar los endpoints: solo esta clase.
 */
public class OperationsAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String ROLES_CLAIM = "roles";
    static final String ROLE_PREFIX = "ROLE_";
    static final String SUPERADMIN = "SUPERADMIN";

    private final Map<String, Set<String>> operationsByRole;

    /**
     * @param operationsByRole rol (con o sin ROLE_, sin importar mayúsculas) -> operaciones
     * @throws IllegalStateException si una operación no existe (un error de escritura no debe dar ni quitar permisos
     *         en silencio)
     */
    public OperationsAuthoritiesConverter(Map<String, List<String>> operationsByRole) {
        Map<String, Set<String>> normalized = new TreeMap<>();
        if (operationsByRole != null) {
            operationsByRole.forEach((role, operations) -> {
                Set<String> ops = new LinkedHashSet<>();
                for (String op : operations == null ? List.<String>of() : operations) {
                    String code = op == null ? "" : op.strip().toUpperCase(Locale.ROOT);
                    if (!ProspeccionOperations.ALL.contains(code)) {
                        throw new IllegalStateException("prospeccion.security.operations-by-role." + role
                                + ": la operación '" + op + "' no existe (válidas: " + ProspeccionOperations.ALL + ")");
                    }
                    ops.add(code);
                }
                normalized.put(normalizeRole(role), ops);
            });
        }
        this.operationsByRole = normalized;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        Set<String> authorities = new LinkedHashSet<>();
        Set<String> operations = new LinkedHashSet<>();
        for (String raw : roles == null ? List.<String>of() : roles) {
            String role = normalizeRole(raw);
            if (role.isEmpty()) {
                continue;
            }
            authorities.add(ROLE_PREFIX + role);
            if (SUPERADMIN.equals(role)) {
                operations.addAll(ProspeccionOperations.ALL);
            } else {
                operations.addAll(operationsByRole.getOrDefault(role, Set.of()));
            }
        }
        if (!operations.isEmpty()) {
            operations.add(ProspeccionOperations.VER);
        }
        operations.forEach(op -> authorities.add(ProspeccionOperations.AUTHORITY_PREFIX + op));
        return authorities.stream().map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
    }

    static String normalizeRole(String role) {
        String r = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        return r.startsWith(ROLE_PREFIX) ? r.substring(ROLE_PREFIX.length()) : r;
    }
}
