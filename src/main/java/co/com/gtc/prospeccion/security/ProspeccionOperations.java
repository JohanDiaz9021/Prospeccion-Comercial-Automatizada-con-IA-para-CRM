package co.com.gtc.prospeccion.security;

import java.util.List;

/**
 * Operaciones del módulo (tarea 2.9). Cada endpoint exige una con {@code @PreAuthorize}; el usuario las tiene como
 * authorities {@code OP_<operación>} (ver {@link OperationsAuthoritiesConverter}).
 * <p>
 * Equivalencia propuesta con las acciones del permission-service de Sueco (para la 1.13):
 * VER = Abrir, EJECUTAR = Crear, APROBAR = Editar (aprobar, editar y descartar borradores), CONFIGURAR = Editar sobre
 * el ítem de menú "Configuración de Prospección".
 */
public final class ProspeccionOperations {

    /** Ver panel, bandeja, búsquedas, cola, créditos y configuración. */
    public static final String VER = "VER";
    /** Interpretar, previsualizar y ejecutar búsquedas; activar o pausar campañas (gasta créditos). */
    public static final String EJECUTAR = "EJECUTAR";
    /** Editar, aprobar, descartar y redactar de nuevo borradores. Aprobar envía el correo. */
    public static final String APROBAR = "APROBAR";
    /** Cambiar el perfil, el mensaje base, el umbral y los límites; probar calificación y redacción. */
    public static final String CONFIGURAR = "CONFIGURAR";

    public static final List<String> ALL = List.of(VER, EJECUTAR, APROBAR, CONFIGURAR);

    public static final String AUTHORITY_PREFIX = "OP_";

    // Expresiones para @PreAuthorize.
    public static final String CAN_VIEW = "hasAuthority('OP_VER')";
    public static final String CAN_RUN = "hasAuthority('OP_EJECUTAR')";
    public static final String CAN_APPROVE = "hasAuthority('OP_APROBAR')";
    public static final String CAN_CONFIGURE = "hasAuthority('OP_CONFIGURAR')";

    private ProspeccionOperations() {
    }
}
