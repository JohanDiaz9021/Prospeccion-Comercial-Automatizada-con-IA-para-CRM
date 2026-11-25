package co.com.gtc.prospeccion.common.constants;

/**
 * TABLECODE (numérico) y CODE (texto) de sy_mastertable que usa el módulo.
 * Los de Crm* salen del código de msa-cotizaciones y mf-cotizaciones. Los propios del módulo (600 a 606) los crea
 * db/scripts/V2__catalogos_y_etiquetas.sql; si el equipo CRM asigna otros TABLECODE, se cambian aquí y en el script.
 */
public final class MasterTableCodes {

    private MasterTableCodes() {
    }

    // ---------- Catálogos que ya existen en el CRM ----------

    /** Estado del prospecto (pm_leaflet.MT_STATUSID). Fuente: LeafletServiceImpl, ProspectConversionService. */
    public static final class CrmProspectStatus {
        public static final Integer TABLECODE = 221;
        public static final String NEW = "1";
        public static final String INACTIVE = "2";
        public static final String CLIENT = "3";

        private CrmProspectStatus() {
        }
    }

    /** Tipo de medio de contacto (pm_leafletcontactmedium). Fuente: ILeafletRepository, modal-contact. */
    public static final class CrmContactMediumType {
        public static final Integer TABLECODE = 10;
        public static final String PHONE = "1";
        public static final String EMAIL = "5";

        private CrmContactMediumType() {
        }
    }

    /** Tipo de identificación (pm_leaflet y pm_leafletcontact). */
    public static final class CrmIdentificationType {
        public static final Integer TABLECODE = 35;

        private CrmIdentificationType() {
        }
    }

    /** Estado de la tarea (pm_task.MT_STATUS). Fuente: TaskServiceImpl. */
    public static final class CrmTaskStatus {
        public static final Integer TABLECODE = 251;
        public static final String PENDING = "1";
        public static final String INCOMPLETE = "2";
        public static final String COMPLETED = "3";

        private CrmTaskStatus() {
        }
    }

    /** Tipo, prioridad y periodicidad de la tarea. Los CODE a usar están por confirmar con el equipo CRM. */
    public static final class CrmTaskCatalogs {
        public static final Integer TYPE_TABLECODE = 246;
        public static final Integer PRIORITY_TABLECODE = 247;
        public static final Integer PERIODICITY_TABLECODE = 248;

        private CrmTaskCatalogs() {
        }
    }

    // ---------- Catálogos propios del módulo (V2__catalogos_y_etiquetas.sql) ----------

    /** Estados de la_searchrun.MT_STATUSID. */
    public static final class SearchRunStatus {
        public static final Integer TABLECODE = 600;
        public static final String PENDING = "1";
        public static final String RUNNING = "2";
        public static final String FINISHED = "3";
        public static final String FAILED = "4";

        private SearchRunStatus() {
        }
    }

    /** Estados de la_draft.MT_STATUSID. */
    public static final class DraftStatus {
        public static final Integer TABLECODE = 601;
        public static final String PENDING_REVIEW = "1";
        public static final String APPROVED = "2";
        public static final String DISCARDED = "3";
        public static final String EXPIRED = "4";

        private DraftStatus() {
        }
    }

    /** Estados de la_sendqueue.MT_STATUSID. */
    public static final class SendQueueStatus {
        public static final Integer TABLECODE = 602;
        public static final String QUEUED = "1";
        public static final String SENT = "2";
        public static final String FAILED = "3";
        public static final String CANCELLED = "4";

        private SendQueueStatus() {
        }
    }

    /** Tipos de la_emailevent.MT_TYPEID. */
    public static final class EmailEventType {
        public static final Integer TABLECODE = 603;
        public static final String SENT = "1";
        public static final String REPLY = "2";
        public static final String BOUNCE = "3";
        public static final String UNSUBSCRIBE = "4";
        /** Apertura por píxel (5.4): dato aproximado, apagado por defecto. */
        public static final String OPEN = "5";
        /** 5.6: quien respondió pasó al vendedor (tarea creada en el CRM sobre su prospecto). */
        public static final String HANDOFF = "6";

        private EmailEventType() {
        }
    }

    /** Catálogos de pm_task en el CRM (msa-cotizaciones, ITaskRepository y TaskServiceImpl). */
    public static final class CrmTask {
        public static final Integer TYPE_TABLECODE = 246;
        public static final Integer PRIORITY_TABLECODE = 247;
        public static final Integer PERIODICITY_TABLECODE = 248;
        public static final Integer STATUS_TABLECODE = 251;
        public static final String STATUS_PENDING = "1";

        private CrmTask() {
        }
    }

    /** Motivos de la_suppression.MT_REASONID. */
    public static final class SuppressionReason {
        public static final Integer TABLECODE = 604;
        public static final String UNSUBSCRIBE = "1";
        public static final String HARD_BOUNCE = "2";
        public static final String COMPLAINT = "3";
        public static final String MANUAL = "4";

        private SuppressionReason() {
        }
    }

    /** Nivel del cargo para la pantalla de filtros. SHORTCUT = valor de person_seniorities[] en Apollo. */
    public static final class Seniority {
        public static final Integer TABLECODE = 605;

        private Seniority() {
        }
    }

    /** Tamaño de empresa para la pantalla de filtros. SHORTCUT = rango de organization_num_employees_ranges[]. */
    public static final class CompanySize {
        public static final Integer TABLECODE = 606;

        private CompanySize() {
        }
    }
}
