package co.com.gtc.prospeccion.common.constants;

/**
 * Valores de la_evidence.SOURCE y la_evidence.FIELD. ProspectCreationService guarda la evidencia de cada dato del
 * contacto al crearlo; la redacción (4.3) solo usa lo que está aquí, para no inventar datos.
 */
public final class EvidenceFields {

    private EvidenceFields() {
    }

    public static final String SOURCE_APOLLO = "APOLLO";
    public static final String SOURCE_HUNTER = "HUNTER";

    /** Primer nombre (Apollo first_name). */
    public static final String FIRST_NAME = "first_name";
    /** Cargo (Apollo title). */
    public static final String TITLE = "title";
    /** Empresa (Apollo organization.name). */
    public static final String ORGANIZATION_NAME = "organization_name";
    /** Sector (Apollo organization.industry). */
    public static final String INDUSTRY = "industry";
    /** Empleados (Apollo organization.estimated_num_employees). */
    public static final String COMPANY_SIZE = "company_size";
    /** Ciudad de la persona (Apollo city). */
    public static final String CITY = "city";
    /** Correo de trabajo aceptado en la 4.1 (fuente APOLLO o HUNTER). Gemini no lo recibe; lo usa la cola de envío. */
    public static final String EMAIL = "email";
    /** Ciudad de la empresa (Apollo organization.city): se usa si falta la de la persona. */
    public static final String ORGANIZATION_CITY = "organization_city";
}
