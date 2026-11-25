package co.com.gtc.prospeccion.common.constants;

/**
 * Claves lbl* de las respuestas (los textos viven en msa-params-crm, nunca aquí).
 * Se crean con db/scripts/V2__catalogos_y_etiquetas.sql (clave en TABLECODE 300, texto en 301); si una ya existe
 * en el tenant, el script la deja como está.
 */
public final class MessageKeys {

    public static final String SUCCESS = "lblSuccess";
    public static final String VALIDATION_ERROR = "lblValidationError";
    public static final String NOT_FOUND = "lblNotFound";
    public static final String ACCESS_DENIED = "lblAccessDenied";
    public static final String EXTERNAL_SERVICE_ERROR = "lblExternalServiceError";
    public static final String NOT_IMPLEMENTED = "lblNotImplemented";
    public static final String UNEXPECTED_ERROR = "lblUnexpectedError";
    /** Falta el token o no es válido (firma, vencimiento). */
    public static final String UNAUTHORIZED = "lblUnauthorized";
    /** El token no trae db_code, el header del tenant no coincide o el módulo no está habilitado para el tenant. */
    public static final String TENANT_NOT_ALLOWED = "lblTenantNotAllowed";
    public static final String USER_NOT_FOUND = "lblUserNotFound";
    /** Otra persona cambió el dato mientras se editaba (409). */
    public static final String CONFLICT = "lblConflict";
    /** El perfil de interés no es un JSON válido. */
    public static final String PROFILE_INVALID = "lblProfileInvalid";
    /** El perfil o el mensaje base todavía tienen marcas [validar] sin resolver. */
    public static final String PENDING_VALIDATION = "lblPendingValidation";
    /** Se intentó calificar contactos sin perfil de interés en la configuración del tenant. */
    public static final String PROFILE_MISSING = "lblProfileMissing";
    /** Se alcanzó el tope de créditos por campaña o el presupuesto mensual de un proveedor. */
    public static final String CREDIT_LIMIT = "lblCreditLimit";
    /** Se intentó redactar un correo sin mensaje base en la configuración del tenant. */
    public static final String BASE_MESSAGE_MISSING = "lblBaseMessageMissing";
    /** El contacto ya tiene un borrador aprobado (en cola o enviado): no se redacta otro. */
    public static final String DRAFT_ALREADY_APPROVED = "lblDraftAlreadyApproved";
    /** Gemini no logró un borrador que cumpla las reglas del mensaje base. */
    public static final String DRAFT_NOT_VALID = "lblDraftNotValid";
    /** Se intentó editar, descartar o aprobar un borrador que ya no está "Por revisar". */
    public static final String DRAFT_NOT_PENDING = "lblDraftNotPending";
    /** El contacto no tiene correo de trabajo en su evidencia: no se puede encolar. */
    public static final String CONTACT_WITHOUT_EMAIL = "lblContactWithoutEmail";
    /** El correo del contacto está en la lista de bloqueo (se dio de baja, rebotó o se quejó). */
    public static final String CONTACT_SUPPRESSED = "lblContactSuppressed";
    /** Gemini no encontró en la búsqueda a quién buscar (cargo, sector o palabra clave). */
    public static final String SEARCH_NOT_UNDERSTOOD = "lblSearchNotUnderstood";
    /** Los filtros revisados no dicen a quién buscar (solo ubicación o tamaño). */
    public static final String SEARCH_FILTERS_EMPTY = "lblSearchFiltersEmpty";
    /** No hay configuración activa del tenant (perfil, umbral y mensaje base). */
    public static final String CONFIG_MISSING = "lblConfigMissing";

    private MessageKeys() {
    }
}
