package co.com.gtc.prospeccion.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Propiedades tipadas del módulo: prefijos "prospeccion", "integrations" y "aws.ssm".
 * Nunca contienen llaves: solo el NOMBRE del parámetro SSM donde vive cada llave.
 */
@Configuration
@EnableConfigurationProperties({
        PropertiesConfig.ProspeccionProperties.class,
        PropertiesConfig.IntegrationsProperties.class,
        PropertiesConfig.AwsSsmProperties.class
})
public class PropertiesConfig {

    @Getter
    @Setter
    @ConfigurationProperties(prefix = "prospeccion")
    public static class ProspeccionProperties {

        /** Tenants (db_code) donde corre el robot. */
        private List<String> tenantsEnabled = new ArrayList<>();

        /** Zona horaria de negocio: la Ley 2300 se evalúa en hora de Colombia. */
        private String timezone = "America/Bogota";

        private Ley2300 ley2300 = new Ley2300();
        private Jobs jobs = new Jobs();
        private Mailbox mailbox = new Mailbox();
        private Unsubscribe unsubscribe = new Unsubscribe();
        private EmailDiscovery emailDiscovery = new EmailDiscovery();
        private Search search = new Search();
        private Tracking tracking = new Tracking();
        private Credits credits = new Credits();
        private Legal legal = new Legal();
        private Handoff handoff = new Handoff();

        /** Solo perfil local: tenant leído de variables de entorno en lugar de SSM. */
        private LocalTenant localTenant = new LocalTenant();

        private Security security = new Security();
        private TenantPool tenantPool = new TenantPool();

        /** El módulo solo atiende a los tenants de tenants-enabled (peticiones, jobs y bases de datos). */
        public boolean isTenantEnabled(String tenantCode) {
            return tenantCode != null && tenantsEnabled.stream().anyMatch(t -> t.trim().equals(tenantCode));
        }
    }

    @Getter
    @Setter
    public static class Security {
        /**
         * Clave HMAC en Base64 con la que el CRM firma el JWT. Es la misma JWT_SECRET_KEY de msa-apigateway-crm.
         * Nunca se registra en logs.
         */
        private String jwtSecret;
        /**
         * Tarea 2.9: operaciones del módulo (VER, EJECUTAR, APROBAR, CONFIGURAR) de cada rol del token. SUPERADMIN tiene
         * todas; un rol que no está aquí no tiene ninguna. Se reemplaza por el permission-service con la 1.13.
         */
        private Map<String, List<String>> operationsByRole = new HashMap<>();
    }

    @Getter
    @Setter
    public static class TenantPool {
        /** Conexiones máximas por tenant (un pool Hikari por base de tenant). */
        private int maximumPoolSize = 5;
        private int minimumIdle = 1;
    }

    @Getter
    @Setter
    public static class Ley2300 {
        /** Lunes a viernes. */
        private Window weekdays = new Window();
        /** Sábados. */
        private Window saturday = new Window();
        private boolean sundaysAllowed = false;
        private boolean holidaysAllowed = false;
        /** Días no hábiles extraordinarios que decrete el Gobierno (los festivos de ley se calculan solos). */
        private List<LocalDate> extraHolidays = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Window {
        /** Formato HH:mm, hora de Colombia. */
        private String start;
        /** Formato HH:mm, hora de Colombia. */
        private String end;
    }

    @Getter
    @Setter
    public static class Jobs {
        private Job scheduledSearch = new Job();
        private Job sendDispatcher = new Job();
        private Job mailboxSync = new Job();
        private Job maintenance = new Job();
    }

    @Getter
    @Setter
    public static class Job {
        private boolean enabled = false;
        private String cron;
    }

    @Getter
    @Setter
    public static class Mailbox {
        /** gmail | graph | smtp | none. smtp es para Mailpit en local (tarea 5.2); gmail o graph, cuando TI decida. */
        private String provider = "none";
        /**
         * Tarea 5.4: cada lectura revisa lo que llegó en estas últimas horas. Cada mensaje se procesa una sola vez (por
         * su ID en el proveedor), así que repasar la ventana no repite nada y no hace falta guardar dónde se quedó.
         */
        private int lookbackHours = 48;
        /** Buzón de prospección que envía. Su dominio va en el Message-ID de cada correo. */
        private String fromAddress;
        /** Nombre que ve el destinatario (la firma del mensaje base). */
        private String fromName = "Equipo Comercial GTC";
        /** Intentos de envío antes de marcar el correo como fallido (un error permanente no se reintenta). */
        private int maxAttempts = 3;
        /** Espera antes de reintentar: estos minutos por el número de intentos (15, 30...). */
        private int retryMinutes = 15;
        /** Correos como máximo por cada pasada del despachador, como freno de seguridad. */
        private int maxPerRun = 10;
        private Smtp smtp = new Smtp();
    }

    /**
     * SMTP (tarea 5.2). Pensado para Mailpit en local: atrapa los correos y nunca los entrega. Por seguridad solo acepta
     * un servidor local; otro servidor exige allow-remote-host = true a propósito.
     */
    @Getter
    @Setter
    public static class Smtp {
        private String host;
        private int port = 1025;
        private String username;
        private String password;
        private boolean starttls;
        private boolean allowRemoteHost;
        /** API de Mailpit (http://localhost:8025): si está, también se lee el buzón desde ahí (seguimiento 5.4). */
        private String mailpitApiUrl;
    }

    /**
     * Datos del pie legal de cada correo (tarea 1.10, docs/negocio/mensaje-base.md). Sin NIT, dirección o política de
     * datos no se envía nada: el pie no puede salir con [completar].
     */
    /**
     * Tarea 5.6: quien responde pasa al vendedor con una tarea en el CRM (pm_task sobre su prospecto). Los CODE de los
     * catálogos 246 (tipo), 247 (prioridad) y 248 (periodicidad) los confirma el equipo CRM; vacío = no se envía.
     */
    @Getter
    @Setter
    public static class Handoff {
        private boolean enabled = true;
        /** Obligatorio para crear la tarea: CODE de sy_mastertable 246. */
        private String taskTypeCode = "1";
        private String priorityCode;
        private String periodicityCode;
        /** Días hábiles (sin fines de semana ni festivos) para contactar a quien respondió. */
        private int dueBusinessDays = 1;
        /** Hora de vencimiento de la tarea, HH:mm (hora de Colombia). */
        private String dueHour = "10:00";
        /** Respuestas que se pasan al vendedor por cada pasada (las demás, en la siguiente). */
        private int maxPerRun = 50;
    }

    @Getter
    @Setter
    public static class Legal {
        private String companyName = "GTC Corporation";
        private String nit;
        private String address;
        private String privacyPolicyUrl;
    }

    /** Seguimiento de los correos enviados (tarea 5.4). */
    @Getter
    @Setter
    public static class Tracking {
        /** Rebotes temporales a una misma dirección antes de bloquearla como si fuera un rebote duro. */
        private int softBounceLimit = 3;
        private OpenPixel openPixel = new OpenPixel();
    }

    /**
     * Píxel de apertura: dato APROXIMADO (Apple y algunos antivirus abren solos los correos; otros bloquean las
     * imágenes). Apagado por defecto: activarlo requiere el visto bueno de Jurídica (1.10) y abrir en el gateway la ruta
     * /msa-prospeccion/public/open.
     */
    @Getter
    @Setter
    public static class OpenPixel {
        private boolean enabled = false;
        /** URL pública por el gateway, por ejemplo https://.../msa-prospeccion/public/open. */
        private String baseUrl;
    }

    @Getter
    @Setter
    public static class Unsubscribe {
        /** URL pública de la página de baja (en qa/prod, por el gateway). El token va en ?t=. */
        private String baseUrl;
        /** Nombre del parámetro SSM con el secreto de firma del token de baja (qa/prod). */
        private String tokenSecretSsmParameter;
        /** Solo local: secreto directo (UNSUBSCRIBE_TOKEN_SECRET). Si está, no se consulta SSM. */
        private String tokenSecret;
    }

    /**
     * Topes de créditos (tarea 4.2). Cada tenant tiene su propio libro (la_creditledger en su base), así que los topes
     * aplican por tenant. Un tope en 0 significa "sin tope".
     */
    @Getter
    @Setter
    public static class Credits {
        /** Porcentaje del presupuesto mensual a partir del cual se alerta. */
        private int alertPercent = 80;
        private ProviderBudget apollo = new ProviderBudget(new BigDecimal("1000"), new BigDecimal("50"));
        private ProviderBudget hunter = new ProviderBudget(new BigDecimal("500"), new BigDecimal("50"));
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ProviderBudget {
        /** Créditos del mes calendario (hora de Colombia) para este tenant. 0 = sin tope. */
        private BigDecimal monthlyBudget = BigDecimal.ZERO;
        /** Créditos por campaña (una ejecución de búsqueda, la_searchrun). 0 = sin tope. */
        private BigDecimal perSearchRun = BigDecimal.ZERO;
        /** Precio en USD de un crédito, para el costo estimado. Vacío = no se calcula. */
        private BigDecimal unitCostUsd;

        public ProviderBudget(BigDecimal monthlyBudget, BigDecimal perSearchRun) {
            this.monthlyBudget = monthlyBudget;
            this.perSearchRun = perSearchRun;
        }
    }

    /** Reglas para aceptar un correo (tarea 4.1). */
    @Getter
    @Setter
    public static class EmailDiscovery {
        /** Si Apollo marca el correo como "verified", se acepta sin gastar una verificación de Hunter. */
        private boolean trustApolloVerified = true;
        /** Estados de Hunter que se aceptan para enviar (el resto se descarta). */
        private List<String> acceptedHunterStatuses = new ArrayList<>(List.of("valid", "accept_all"));
        /** Puntaje mínimo del Email Finder para aceptar su correo sin verificarlo aparte. */
        private int minFinderScore = 80;
    }

    /** Búsqueda y campañas (tarea 3.2). */
    @Getter
    @Setter
    public static class Search {
        /**
         * Personas NUEVAS (no vistas antes) que procesa cada ejecución. Cada una que pasa la calificación gasta
         * créditos al conseguir el correo, así que este número pone el techo del gasto diario de cada campaña.
         */
        private int maxNewPerRun = 25;
        /** Páginas de Apollo (100 personas cada una) que se revisan buscando personas nuevas. 0 créditos. */
        private int maxPages = 5;
    }

    @Getter
    @Setter
    public static class LocalTenant {
        private boolean enabled = false;
        private String code;
        private String url;
        private String username;
        private String password;
        private String driverClass = "com.mysql.cj.jdbc.Driver";
    }

    @Getter
    @Setter
    @ConfigurationProperties(prefix = "integrations")
    public static class IntegrationsProperties {
        private Provider apollo = new Provider();
        private Provider hunter = new Provider();
        private GeminiProvider gemini = new GeminiProvider();
    }

    @Getter
    @Setter
    public static class Provider {
        private String baseUrl;
        /** Nombre del parámetro SSM con la llave (nunca la llave). */
        private String apiKeySsmParameter;
        /**
         * Solo desarrollo local (APOLLO_API_KEY, HUNTER_API_KEY o GEMINI_API_KEY en el .env). Si está vacía,
         * la llave se lee de SSM (api-key-ssm-parameter). Nunca se registra en logs.
         */
        private String apiKey;
        /** Reintentos ante 429, 5xx o fallas de red (además del primer intento). */
        private int maxRetries = 3;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[baseUrl=" + baseUrl + ", maxRetries=" + maxRetries
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "(SSM)" : "****") + "]";
        }
    }

    @Getter
    @Setter
    public static class GeminiProvider extends Provider {
        /** Modelo (por ejemplo gemini-2.5-flash). Se cambia por variable de entorno, sin tocar el código. */
        private String model;
        /** Contactos por llamada al calificar (3.5): menos llamadas y el perfil viaja una vez por lote. */
        private int qualificationBatchSize = 10;

        @Override
        public String toString() {
            return super.toString().replace("]", ", model=" + model + ", qualificationBatchSize="
                    + qualificationBatchSize + "]");
        }
    }

    @Getter
    @Setter
    @ConfigurationProperties(prefix = "aws.ssm")
    public static class AwsSsmProperties {
        private String region;
        /** Ruta base: [basePath][tenant]/url|username|password|driverclass. */
        private String tenantBasePath;
    }
}
