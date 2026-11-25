-- =====================================================================
-- msa-prospeccion-crm - tablas la_* (Automatización de Leads), modelo de datos v3.5
-- Se ejecuta en la base de CADA tenant habilitado. MySQL 8.0.16 o superior.
--
-- Estándar (leyenda del modelo v3.5):
--   * PK = ID INT(11) AUTO_INCREMENT.
--   * «UK» = llave única de negocio: UK_[TABLA]_IDXnn.
--   * Auditoría: CREATEDDATE / LASTUPDATEDDATE -> sy_datetable (RESTRICT);
--                CREATEDBY / LASTUPDATEDBY     -> sc_user (NO ACTION).
--   * MT_*  -> sy_mastertable.  PM_* -> tablas del CRM (las gestiona msa-cotizaciones).
--   * Todas las tablas la_* tienen FK reales: ninguna referencia queda suelta.
--
-- Por confirmar con el equipo CRM (el diagrama no los trae):
--   * Tipos y largos de cada columna (aquí son una propuesta).
--   * Que los ID del CRM (pm_*, sc_user, sy_*) sean INT con signo: las FK exigen el mismo tipo.
--   * Nombres de FK (FK_[TABLA]_IDXnn) e índices (IX_[TABLA]_IDXnn), por analogía con los UK.
--   * Herramienta de migración (Flyway, Liquibase o scripts manuales): el nombre V1__ sirve para Flyway.
-- Hibernate tiene ddl-auto=none: nunca crea ni altera estas tablas.
-- =====================================================================

-- ---------------------------------------------------------------------
-- la_tenantconfig: configuración del robot. Versionada: solo una fila con FLGACTIVE = 1.
-- ---------------------------------------------------------------------
CREATE TABLE la_tenantconfig (
    ID                   INT(11)      NOT NULL AUTO_INCREMENT,
    PROFILE_OF_INTEREST  TEXT         NULL COMMENT 'JSON del perfil de interés (docs/negocio/perfil-de-interes.md)',
    BASE_MESSAGE         TEXT         NULL COMMENT 'Mensaje base (docs/negocio/mensaje-base.md)',
    SCORE_THRESHOLD      INT(11)      NULL COMMENT 'Puntaje mínimo para gastar créditos y redactar (0-100)',
    DAILY_SEND_LIMIT     INT(11)      NULL,
    MIN_SPACING_SECONDS  INT(11)      NULL,
    MAILBOX_SSM_KEY      VARCHAR(255) NULL COMMENT 'Nombre del parámetro SSM del buzón (nunca la credencial)',
    COMPANY_LINKEDIN_URL VARCHAR(500) NULL COMMENT 'No está en el diagrama v3.5: lo usa la plantilla del correo',
    FLGACTIVE            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = versión vigente',
    CREATEDDATE          INT(11)      NOT NULL,
    CREATEDBY            INT(11)      NOT NULL,
    LASTUPDATEDDATE      INT(11)      NULL,
    LASTUPDATEDBY        INT(11)      NULL,
    PRIMARY KEY (ID),
    -- Solo una versión vigente: la expresión vale NULL en las inactivas y los NULL no chocan en un UNIQUE.
    UNIQUE KEY UK_LA_TENANTCONFIG_IDX01 ((CASE WHEN FLGACTIVE = 1 THEN 1 END)),
    CONSTRAINT FK_LA_TENANTCONFIG_IDX01 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_TENANTCONFIG_IDX02 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_TENANTCONFIG_IDX03 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_TENANTCONFIG_IDX04 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: configuración del robot por tenant (versionada)';

-- ---------------------------------------------------------------------
-- la_searchrun: cada búsqueda ejecutada.
-- ---------------------------------------------------------------------
CREATE TABLE la_searchrun (
    ID                INT(11)       NOT NULL AUTO_INCREMENT,
    TENANTCONFIGID    INT(11)       NOT NULL COMMENT 'Configuración usada',
    PM_LISTCONTACTID  INT(11)       NULL COMMENT 'Lista del CRM donde quedan los contactos',
    NATURAL_QUERY     VARCHAR(1000) NULL,
    FILTERS_JSON      JSON          NOT NULL COMMENT 'Filtros de Apollo',
    FOUND_COUNT       INT(11)       NOT NULL DEFAULT 0,
    NEW_COUNT         INT(11)       NOT NULL DEFAULT 0,
    QUALIFIED_COUNT   INT(11)       NOT NULL DEFAULT 0,
    MT_STATUSID       INT(11)       NOT NULL,
    ERROR_DETAIL      TEXT          NULL,
    FLG_SCHEDULED     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'Campaña: el job la repite cada día hábil (no está en el diagrama v3.5)',
    SCHEDULE_FREQUENCY VARCHAR(10)  NULL COMMENT 'Campaña: DAILY (cada día hábil) o WEEKLY (una vez por semana) (no está en el diagrama v3.5)',
    PARENT_SEARCHRUNID INT(11)      NULL COMMENT 'Campaña que originó esta ejecución automática (no está en el diagrama v3.5)',
    CREATEDDATE       INT(11)       NOT NULL,
    CREATEDBY         INT(11)       NOT NULL,
    LASTUPDATEDDATE   INT(11)       NULL,
    LASTUPDATEDBY     INT(11)       NULL,
    PRIMARY KEY (ID),
    KEY IX_LA_SEARCHRUN_IDX01 (FLG_SCHEDULED),
    CONSTRAINT CK_LA_SEARCHRUN_IDX01 CHECK (SCHEDULE_FREQUENCY IN ('DAILY', 'WEEKLY')),
    CONSTRAINT FK_LA_SEARCHRUN_IDX08 FOREIGN KEY (PARENT_SEARCHRUNID) REFERENCES la_searchrun (ID),
    CONSTRAINT FK_LA_SEARCHRUN_IDX01 FOREIGN KEY (TENANTCONFIGID) REFERENCES la_tenantconfig (ID),
    CONSTRAINT FK_LA_SEARCHRUN_IDX02 FOREIGN KEY (PM_LISTCONTACTID) REFERENCES pm_list_contact (ID),
    CONSTRAINT FK_LA_SEARCHRUN_IDX03 FOREIGN KEY (MT_STATUSID) REFERENCES sy_mastertable (ID),
    CONSTRAINT FK_LA_SEARCHRUN_IDX04 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SEARCHRUN_IDX05 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_SEARCHRUN_IDX06 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SEARCHRUN_IDX07 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: búsquedas ejecutadas';

-- ---------------------------------------------------------------------
-- la_leafletext: datos de la empresa que trae Apollo. 1 a 1 con pm_leaflet.
-- ---------------------------------------------------------------------
CREATE TABLE la_leafletext (
    ID                    INT(11)      NOT NULL AUTO_INCREMENT,
    PM_LEAFLETID          INT(11)      NOT NULL,
    PROVIDER_ID           VARCHAR(100) NULL COMMENT 'ID de la organización en Apollo',
    COMPANY_DOMAIN        VARCHAR(255) NULL,
    INDUSTRY              VARCHAR(150) NULL,
    COMPANY_SIZE          VARCHAR(30)  NULL,
    CITY                  VARCHAR(150) NULL,
    COMPANY_LINKEDIN_URL  VARCHAR(500) NULL,
    CREATEDDATE           INT(11)      NOT NULL,
    CREATEDBY             INT(11)      NOT NULL,
    LASTUPDATEDDATE       INT(11)      NULL,
    LASTUPDATEDBY         INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_LEAFLETEXT_IDX01 (PM_LEAFLETID),
    UNIQUE KEY UK_LA_LEAFLETEXT_IDX02 (PROVIDER_ID),
    UNIQUE KEY UK_LA_LEAFLETEXT_IDX03 (COMPANY_DOMAIN),
    CONSTRAINT FK_LA_LEAFLETEXT_IDX01 FOREIGN KEY (PM_LEAFLETID) REFERENCES pm_leaflet (ID),
    CONSTRAINT FK_LA_LEAFLETEXT_IDX02 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_LEAFLETEXT_IDX03 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_LEAFLETEXT_IDX04 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_LEAFLETEXT_IDX05 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: datos de la empresa (Apollo)';

-- ---------------------------------------------------------------------
-- la_contactext: datos de IA del contacto. 1 a 1 con pm_leafletcontact.
-- ---------------------------------------------------------------------
CREATE TABLE la_contactext (
    ID                   INT(11)      NOT NULL AUTO_INCREMENT,
    PM_LEAFLETCONTACTID  INT(11)      NOT NULL,
    PROVIDER_ID          VARCHAR(100) NOT NULL COMMENT 'ID de la persona en Apollo',
    TITLE                VARCHAR(255) NULL,
    SENIORITY            VARCHAR(50)  NULL,
    LINKEDIN_URL         VARCHAR(500) NULL,
    EMAIL_SOURCE         VARCHAR(20)  NULL COMMENT 'APOLLO o HUNTER',
    EMAIL_VERIFICATION   VARCHAR(30)  NULL,
    SCORE                INT(11)      NULL,
    SCORE_REASON         VARCHAR(500) NULL,
    MODEL                VARCHAR(100) NULL,
    PROMPT_VERSION       VARCHAR(30)  NULL,
    SEARCHRUNID          INT(11)      NULL,
    CREATEDDATE          INT(11)      NOT NULL,
    CREATEDBY            INT(11)      NOT NULL,
    LASTUPDATEDDATE      INT(11)      NULL,
    LASTUPDATEDBY        INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_CONTACTEXT_IDX01 (PM_LEAFLETCONTACTID),
    UNIQUE KEY UK_LA_CONTACTEXT_IDX02 (PROVIDER_ID),
    CONSTRAINT FK_LA_CONTACTEXT_IDX01 FOREIGN KEY (PM_LEAFLETCONTACTID) REFERENCES pm_leafletcontact (ID),
    CONSTRAINT FK_LA_CONTACTEXT_IDX02 FOREIGN KEY (SEARCHRUNID) REFERENCES la_searchrun (ID),
    CONSTRAINT FK_LA_CONTACTEXT_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_CONTACTEXT_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_CONTACTEXT_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_CONTACTEXT_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: datos de IA del contacto';

-- ---------------------------------------------------------------------
-- la_apolloseen: memoria de Apollo (no pagar dos veces por la misma persona).
-- ---------------------------------------------------------------------
CREATE TABLE la_apolloseen (
    ID                   INT(11)      NOT NULL AUTO_INCREMENT,
    PROVIDER_ID          VARCHAR(100) NOT NULL COMMENT 'ID de la persona en Apollo',
    RESULT               VARCHAR(20)  NOT NULL,
    SCORE                INT(11)      NULL,
    SEARCHRUNID          INT(11)      NOT NULL,
    PM_LEAFLETCONTACTID  INT(11)      NULL COMMENT 'Null si no se creó en el CRM',
    CREATEDDATE          INT(11)      NOT NULL,
    CREATEDBY            INT(11)      NOT NULL,
    LASTUPDATEDDATE      INT(11)      NULL,
    LASTUPDATEDBY        INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_APOLLOSEEN_IDX01 (PROVIDER_ID),
    CONSTRAINT CK_LA_APOLLOSEEN_IDX01 CHECK (RESULT IN ('CREADO', 'DESCARTADO_IA', 'SIN_CORREO', 'EXCLUIDO')),
    CONSTRAINT FK_LA_APOLLOSEEN_IDX01 FOREIGN KEY (SEARCHRUNID) REFERENCES la_searchrun (ID),
    CONSTRAINT FK_LA_APOLLOSEEN_IDX02 FOREIGN KEY (PM_LEAFLETCONTACTID) REFERENCES pm_leafletcontact (ID),
    CONSTRAINT FK_LA_APOLLOSEEN_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_APOLLOSEEN_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_APOLLOSEEN_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_APOLLOSEEN_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: personas de Apollo ya procesadas';

-- ---------------------------------------------------------------------
-- la_evidence: origen de cada dato del contacto.
-- ---------------------------------------------------------------------
CREATE TABLE la_evidence (
    ID                   INT(11)       NOT NULL AUTO_INCREMENT,
    PM_LEAFLETCONTACTID  INT(11)       NOT NULL,
    SOURCE               VARCHAR(30)   NOT NULL COMMENT 'APOLLO, HUNTER...',
    FIELD                VARCHAR(50)   NOT NULL,
    VALUE                VARCHAR(1000) NULL,
    SOURCE_URL           VARCHAR(500)  NULL,
    CREATEDDATE          INT(11)       NOT NULL,
    CREATEDBY            INT(11)       NOT NULL,
    LASTUPDATEDDATE      INT(11)       NULL,
    LASTUPDATEDBY        INT(11)       NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_EVIDENCE_IDX01 (PM_LEAFLETCONTACTID, SOURCE, FIELD),
    CONSTRAINT FK_LA_EVIDENCE_IDX01 FOREIGN KEY (PM_LEAFLETCONTACTID) REFERENCES pm_leafletcontact (ID),
    CONSTRAINT FK_LA_EVIDENCE_IDX02 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_EVIDENCE_IDX03 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_EVIDENCE_IDX04 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_EVIDENCE_IDX05 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: evidencia de cada dato';

-- ---------------------------------------------------------------------
-- la_draft: borradores de correo. Cada versión es una fila.
-- ---------------------------------------------------------------------
CREATE TABLE la_draft (
    ID                   INT(11)      NOT NULL AUTO_INCREMENT,
    PM_LEAFLETCONTACTID  INT(11)      NOT NULL,
    VERSION              INT(11)      NOT NULL DEFAULT 1,
    SUBJECT              VARCHAR(255) NOT NULL,
    BODY                 TEXT         NOT NULL COMMENT 'HTML sanitizado',
    MT_STATUSID          INT(11)      NOT NULL,
    EDITED_BY_HUMAN      TINYINT(1)   NOT NULL DEFAULT 0,
    MODEL                VARCHAR(100) NULL COMMENT 'Modelo de Gemini que redactó (no está en el diagrama v3.5)',
    PROMPT_VERSION       VARCHAR(30)  NULL COMMENT 'Versión de prompts/draft-email.txt (no está en el diagrama v3.5)',
    DISCARD_REASON       VARCHAR(500) NULL COMMENT 'Motivo al descartar (no está en el diagrama v3.5)',
    APPROVEDBY           INT(11)      NULL,
    APPROVEDDATE         INT(11)      NULL,
    CREATEDDATE          INT(11)      NOT NULL,
    CREATEDBY            INT(11)      NOT NULL,
    LASTUPDATEDDATE      INT(11)      NULL,
    LASTUPDATEDBY        INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_DRAFT_IDX01 (PM_LEAFLETCONTACTID, VERSION),
    CONSTRAINT FK_LA_DRAFT_IDX01 FOREIGN KEY (PM_LEAFLETCONTACTID) REFERENCES pm_leafletcontact (ID),
    CONSTRAINT FK_LA_DRAFT_IDX02 FOREIGN KEY (MT_STATUSID) REFERENCES sy_mastertable (ID),
    CONSTRAINT FK_LA_DRAFT_IDX03 FOREIGN KEY (APPROVEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_DRAFT_IDX04 FOREIGN KEY (APPROVEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_DRAFT_IDX05 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_DRAFT_IDX06 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_DRAFT_IDX07 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_DRAFT_IDX08 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: borradores de correo';

-- ---------------------------------------------------------------------
-- la_sendqueue: cola de envío (una fila por borrador aprobado).
-- ---------------------------------------------------------------------
CREATE TABLE la_sendqueue (
    ID               INT(11)      NOT NULL AUTO_INCREMENT,
    DRAFTID          INT(11)      NOT NULL,
    RECIPIENT_EMAIL  VARCHAR(320) NULL COMMENT 'Destinatario en minúsculas (no está en el diagrama v3.5)',
    SCHEDULED_AT     DATETIME     NOT NULL COMMENT 'UTC',
    SENT_AT          DATETIME     NULL COMMENT 'UTC',
    ATTEMPTS         INT(11)      NOT NULL DEFAULT 0,
    VERSION          INT(11)      NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    MT_STATUSID      INT(11)      NOT NULL,
    PROVIDER_ID      VARCHAR(255) NULL COMMENT 'ID del mensaje en Gmail o Graph',
    THREAD_ID        VARCHAR(255) NULL,
    CREATEDDATE      INT(11)      NOT NULL,
    CREATEDBY        INT(11)      NOT NULL,
    LASTUPDATEDDATE  INT(11)      NULL,
    LASTUPDATEDBY    INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_SENDQUEUE_IDX01 (DRAFTID),
    -- El despachador busca pendientes por estado y hora programada (FOR UPDATE SKIP LOCKED).
    KEY IX_LA_SENDQUEUE_IDX01 (MT_STATUSID, SCHEDULED_AT),
    -- La baja (5.5) cancela los otros envíos pendientes al mismo correo.
    KEY IX_LA_SENDQUEUE_IDX02 (RECIPIENT_EMAIL),
    CONSTRAINT FK_LA_SENDQUEUE_IDX01 FOREIGN KEY (DRAFTID) REFERENCES la_draft (ID),
    CONSTRAINT FK_LA_SENDQUEUE_IDX02 FOREIGN KEY (MT_STATUSID) REFERENCES sy_mastertable (ID),
    CONSTRAINT FK_LA_SENDQUEUE_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SENDQUEUE_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_SENDQUEUE_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SENDQUEUE_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: cola de envío';

-- ---------------------------------------------------------------------
-- la_emailevent: envío, respuesta, rebote o baja de un correo.
-- ---------------------------------------------------------------------
CREATE TABLE la_emailevent (
    ID               INT(11)      NOT NULL AUTO_INCREMENT,
    SENDQUEUEID      INT(11)      NOT NULL,
    MT_TYPEID        INT(11)      NOT NULL,
    OCCURRED_AT      DATETIME     NOT NULL COMMENT 'UTC',
    PROVIDER_ID      VARCHAR(255) NULL COMMENT 'ID del evento en Gmail o Graph (null en la baja por enlace)',
    CREATEDDATE      INT(11)      NOT NULL,
    CREATEDBY        INT(11)      NOT NULL,
    LASTUPDATEDDATE  INT(11)      NULL,
    LASTUPDATEDBY    INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_EMAILEVENT_IDX01 (PROVIDER_ID),
    CONSTRAINT FK_LA_EMAILEVENT_IDX01 FOREIGN KEY (SENDQUEUEID) REFERENCES la_sendqueue (ID),
    CONSTRAINT FK_LA_EMAILEVENT_IDX02 FOREIGN KEY (MT_TYPEID) REFERENCES sy_mastertable (ID),
    CONSTRAINT FK_LA_EMAILEVENT_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_EMAILEVENT_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_EMAILEVENT_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_EMAILEVENT_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: eventos de correo';

-- ---------------------------------------------------------------------
-- la_suppression: correos a los que nunca se vuelve a escribir.
-- ---------------------------------------------------------------------
CREATE TABLE la_suppression (
    ID               INT(11)      NOT NULL AUTO_INCREMENT,
    EMAIL            VARCHAR(320) NOT NULL COMMENT 'En minúsculas',
    DOMAIN           VARCHAR(255) NOT NULL,
    MT_REASONID      INT(11)      NOT NULL COMMENT 'Baja, rebote duro, queja',
    SENDQUEUEID      INT(11)      NULL COMMENT 'Envío que originó la baja',
    CREATEDDATE      INT(11)      NOT NULL,
    CREATEDBY        INT(11)      NOT NULL,
    LASTUPDATEDDATE  INT(11)      NULL,
    LASTUPDATEDBY    INT(11)      NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_LA_SUPPRESSION_IDX01 (EMAIL),
    KEY IX_LA_SUPPRESSION_IDX01 (DOMAIN),
    CONSTRAINT FK_LA_SUPPRESSION_IDX01 FOREIGN KEY (MT_REASONID) REFERENCES sy_mastertable (ID),
    CONSTRAINT FK_LA_SUPPRESSION_IDX02 FOREIGN KEY (SENDQUEUEID) REFERENCES la_sendqueue (ID),
    CONSTRAINT FK_LA_SUPPRESSION_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SUPPRESSION_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_SUPPRESSION_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_SUPPRESSION_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: lista de supresión';

-- ---------------------------------------------------------------------
-- la_creditledger: créditos consumidos en Apollo, Hunter y Gemini.
-- ---------------------------------------------------------------------
CREATE TABLE la_creditledger (
    ID                   INT(11)        NOT NULL AUTO_INCREMENT,
    PROVIDER             VARCHAR(20)    NOT NULL COMMENT 'APOLLO, HUNTER o GEMINI',
    OPERATION            VARCHAR(50)    NOT NULL,
    CREDITS              DECIMAL(12, 4) NOT NULL DEFAULT 0,
    COST_USD             DECIMAL(12, 6) NULL,
    SEARCHRUNID          INT(11)        NULL COMMENT 'Null si el gasto no es de una búsqueda',
    PM_LEAFLETCONTACTID  INT(11)        NULL,
    CREATEDDATE          INT(11)        NOT NULL,
    CREATEDBY            INT(11)        NOT NULL,
    LASTUPDATEDDATE      INT(11)        NULL,
    LASTUPDATEDBY        INT(11)        NULL,
    PRIMARY KEY (ID),
    CONSTRAINT FK_LA_CREDITLEDGER_IDX01 FOREIGN KEY (SEARCHRUNID) REFERENCES la_searchrun (ID),
    CONSTRAINT FK_LA_CREDITLEDGER_IDX02 FOREIGN KEY (PM_LEAFLETCONTACTID) REFERENCES pm_leafletcontact (ID),
    CONSTRAINT FK_LA_CREDITLEDGER_IDX03 FOREIGN KEY (CREATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_CREDITLEDGER_IDX04 FOREIGN KEY (CREATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_LA_CREDITLEDGER_IDX05 FOREIGN KEY (LASTUPDATEDDATE) REFERENCES sy_datetable (ID) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT FK_LA_CREDITLEDGER_IDX06 FOREIGN KEY (LASTUPDATEDBY) REFERENCES sc_user (ID) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Leads: créditos consumidos';
