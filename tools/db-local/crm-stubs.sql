-- =====================================================================
-- SOLO DESARROLLO LOCAL. Versión mínima de las tablas del CRM que usan los scripts del módulo
-- (FK de V1__prospeccion.sql y datos de V2__catalogos_y_etiquetas.sql), para probarlos en el MySQL de
-- docker-compose.local.yml. Se reemplaza por la estructura real cuando el equipo CRM entregue el DDL o el
-- respaldo anonimizado (tarea 2.11). Nunca se ejecuta en qa ni en prod: allá estas tablas ya existen y son del CRM.
-- Columnas tomadas de las entidades de msa-params y msa-cotizaciones; datos inventados.
-- Orden local: crm-stubs.sql -> V1__prospeccion.sql -> V2__catalogos_y_etiquetas.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS sc_user (
    ID        INT(11)      NOT NULL AUTO_INCREMENT,
    LOGIN     VARCHAR(100) NOT NULL,
    NAME      VARCHAR(100) NULL,
    LASTNAME  VARCHAR(100) NULL,
    EMAIL     VARCHAR(150) NULL,
    PRIMARY KEY (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS sy_datetable (
    ID        INT(11)    NOT NULL AUTO_INCREMENT,
    DATE      DATE       NOT NULL,
    DATECHAR  VARCHAR(8) NOT NULL COMMENT 'Formato por confirmar con el CRM (yyyyMMdd o ddMMyyyy)',
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SY_DATETABLE_IDX01 (DATE)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS sy_mastertable (
    ID                  INT(11)      NOT NULL AUTO_INCREMENT,
    CODE                VARCHAR(20)  NOT NULL,
    TABLECODE           INT(11)      NOT NULL,
    VALUE               VARCHAR(255) NULL,
    OBSERVATION         VARCHAR(255) NULL,
    LANGUAJEID          INT(11)      NULL,
    SHORTCUT            VARCHAR(50)  NULL,
    MODULE              VARCHAR(50)  NULL,
    STATUS              VARCHAR(10)  NULL,
    MAINTENANCEPERUSER  TINYINT      NULL,
    DEFAULTVAL          TINYINT      NULL,
    PRIMARY KEY (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS sy_relationshiplabel (
    ID               INT(11)     NOT NULL AUTO_INCREMENT,
    MT_LABELBACKID   VARCHAR(20) NOT NULL,
    MT_LABELFRONTID  VARCHAR(20) NOT NULL,
    MT_LANGUAGEID    INT(11)     NOT NULL,
    CREATEDDATE      INT(11)     NULL,
    CREATEDBY        INT(11)     NULL,
    LASTUPDATEDDATE  INT(11)     NULL,
    UPDATEDBY        INT(11)     NULL,
    PRIMARY KEY (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS pm_leaflet (
    ID         INT(11)      NOT NULL AUTO_INCREMENT,
    FIRSTNAME  VARCHAR(150) NOT NULL,
    PRIMARY KEY (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS pm_leafletcontact (
    ID            INT(11) NOT NULL AUTO_INCREMENT,
    PM_LEAFLETID  INT(11) NOT NULL,
    PRIMARY KEY (ID),
    CONSTRAINT FK_PM_LEAFLETCONTACT_STUB FOREIGN KEY (PM_LEAFLETID) REFERENCES pm_leaflet (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS pm_list_contact (
    ID    INT(11)      NOT NULL AUTO_INCREMENT,
    NAME  VARCHAR(150) NOT NULL,
    PRIMARY KEY (ID)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------- Datos inventados ----------

INSERT IGNORE INTO sc_user (ID, LOGIN, NAME, LASTNAME, EMAIL)
VALUES (1, 'admin.local', 'Admin', 'Local', 'admin.local@example.invalid');

-- Calendario 2026-2027 (DATECHAR en yyyyMMdd mientras el CRM confirma el formato).
INSERT IGNORE INTO sy_datetable (DATE, DATECHAR)
WITH RECURSIVE dias (d) AS (
    SELECT DATE('2026-01-01')
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dias WHERE d < '2027-12-31'
)
SELECT d, DATE_FORMAT(d, '%Y%m%d') FROM dias;

-- Idioma (TABLECODE 1) y catálogos del CRM que usa el módulo: estado del prospecto (221) y medio de contacto (10).
INSERT IGNORE INTO sy_mastertable (ID, TABLECODE, CODE, VALUE, LANGUAJEID, SHORTCUT, MAINTENANCEPERUSER, DEFAULTVAL) VALUES
    (1, 1, '0', 'Idiomas', 2, NULL, 0, 0),
    (2, 1, '1', 'Español', 2, 'es', 0, 1),
    (3, 221, '0', 'Estado del prospecto', 2, NULL, 0, 0),
    (4, 221, '1', 'Nuevo', 2, NULL, 0, 1),
    (5, 221, '2', 'Inactivo', 2, NULL, 0, 0),
    (6, 221, '3', 'Cliente', 2, NULL, 0, 0),
    (7, 10, '0', 'Tipo de medio de contacto', 2, NULL, 0, 0),
    (8, 10, '1', 'Teléfono', 2, NULL, 0, 0),
    (9, 10, '5', 'Correo', 2, NULL, 0, 0);
