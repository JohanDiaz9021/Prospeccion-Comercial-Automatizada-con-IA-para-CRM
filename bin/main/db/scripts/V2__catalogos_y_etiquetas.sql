-- =====================================================================
-- msa-prospeccion-crm - catálogos (sy_mastertable) y etiquetas lbl* del módulo (tarea 2.8)
-- Se ejecuta en la base de CADA tenant habilitado, después de V1__prospeccion.sql. MySQL 8.
--
-- Cómo guarda el CRM estos datos (leído del código de msa-params-crm):
--   * Catálogo: filas de sy_mastertable con el mismo TABLECODE. CODE '0' = encabezado (nombre del catálogo);
--     CODE '1', '2'... = valores. Una sola fila por (TABLECODE, CODE): msa-params y msa-cotizaciones lo exigen.
--   * Idioma: LANGUAJEID = sy_mastertable.ID de un idioma (TABLECODE 1).
--   * Etiqueta: clave en TABLECODE 300 (VALUE = 'lbl...'), texto en TABLECODE 301 (VALUE = texto) y la
--     relación en sy_relationshiplabel (MT_LABELBACKID / MT_LABELFRONTID = CODE de cada fila, MT_LANGUAGEID).
--
-- Por confirmar con el equipo CRM:
--   * Los TABLECODE 600 a 606 (propuestos). El script se detiene si alguno ya está en uso.
--   * Valores de STATUS y MODULE en sy_mastertable (aquí NULL) y si los textos van en otro idioma además de español.
--
-- Antes de ejecutarlo, revisar los dos parámetros de abajo.
--
-- Si se interrumpe a la mitad (MySQL no deshace los INSERT de un script con DDL), limpiar antes de reintentar:
--   DELETE rl FROM sy_relationshiplabel rl JOIN sy_mastertable mt ON mt.CODE = rl.MT_LABELBACKID AND mt.TABLECODE = 300
--          WHERE mt.OBSERVATION = 'Prospección IA';
--   DELETE FROM sy_mastertable WHERE TABLECODE BETWEEN 600 AND 606
--          OR (TABLECODE IN (300, 301) AND OBSERVATION = 'Prospección IA');
-- =====================================================================

-- Usuario del CRM que queda como creador de las etiquetas (sc_user.LOGIN).
SET @login_creador = 'admin.local';
-- ID del idioma español en sy_mastertable (TABLECODE 1). NULL = buscarlo por SHORTCUT 'es' o VALUE 'Español'.
SET @idioma_id = NULL;

SET @idioma_id = COALESCE(@idioma_id, (
    SELECT ID FROM sy_mastertable
    WHERE TABLECODE = 1 AND CODE <> '0'
      AND (LOWER(SHORTCUT) IN ('es', 'es-co', 'esp') OR LOWER(VALUE) LIKE 'espa%')
    ORDER BY ID LIMIT 1));
SET @usuario_id = (SELECT ID FROM sc_user WHERE LOGIN = @login_creador LIMIT 1);
SET @hoy_id = (SELECT ID FROM sy_datetable WHERE DATE = CURDATE() LIMIT 1);

-- ---------------------------------------------------------------------
-- Validaciones: si algo falla, el script se detiene sin insertar nada.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS la_v2_validar;
DELIMITER $$
CREATE PROCEDURE la_v2_validar()
BEGIN
    IF @idioma_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V2: no se encontró el idioma español (TABLECODE 1). Defina @idioma_id.';
    END IF;
    IF @usuario_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V2: no existe el usuario de @login_creador en sc_user.';
    END IF;
    IF @hoy_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V2: sy_datetable no tiene la fecha de hoy.';
    END IF;
    IF EXISTS (SELECT 1 FROM sy_mastertable WHERE TABLECODE BETWEEN 600 AND 606) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V2: los TABLECODE 600 a 606 ya están en uso. Acordar otros con el equipo CRM.';
    END IF;
END$$
DELIMITER ;
CALL la_v2_validar();
DROP PROCEDURE la_v2_validar;

-- ---------------------------------------------------------------------
-- Catálogos (los CODE y TABLECODE son los de MasterTableCodes en el código del servicio).
-- SHORTCUT guarda el valor que usa Apollo en niveles y tamaños.
-- ---------------------------------------------------------------------
INSERT INTO sy_mastertable (TABLECODE, CODE, VALUE, OBSERVATION, LANGUAJEID, SHORTCUT, MODULE, STATUS, MAINTENANCEPERUSER, DEFAULTVAL)
SELECT c.TABLECODE, c.CODE, c.VALUE, c.OBSERVATION, @idioma_id, c.SHORTCUT, NULL, NULL, 0, 0
FROM (
    -- 600: estado de la búsqueda (la_searchrun.MT_STATUSID)
    SELECT 600 AS TABLECODE, '0' AS CODE, 'Leads - Estado de búsqueda' AS VALUE, 'Prospección IA' AS OBSERVATION, NULL AS SHORTCUT
    UNION ALL SELECT 600, '1', 'Pendiente', NULL, NULL
    UNION ALL SELECT 600, '2', 'En ejecución', NULL, NULL
    UNION ALL SELECT 600, '3', 'Terminada', NULL, NULL
    UNION ALL SELECT 600, '4', 'Fallida', NULL, NULL
    -- 601: estado del borrador (la_draft.MT_STATUSID)
    UNION ALL SELECT 601, '0', 'Leads - Estado de borrador', 'Prospección IA', NULL
    UNION ALL SELECT 601, '1', 'Por revisar', NULL, NULL
    UNION ALL SELECT 601, '2', 'Aprobado', NULL, NULL
    UNION ALL SELECT 601, '3', 'Descartado', NULL, NULL
    UNION ALL SELECT 601, '4', 'Vencido', NULL, NULL
    -- 602: estado del envío (la_sendqueue.MT_STATUSID)
    UNION ALL SELECT 602, '0', 'Leads - Estado de envío', 'Prospección IA', NULL
    UNION ALL SELECT 602, '1', 'En cola', NULL, NULL
    UNION ALL SELECT 602, '2', 'Enviado', NULL, NULL
    UNION ALL SELECT 602, '3', 'Fallido', NULL, NULL
    UNION ALL SELECT 602, '4', 'Cancelado', NULL, NULL
    -- 603: tipo de evento de correo (la_emailevent.MT_TYPEID)
    UNION ALL SELECT 603, '0', 'Leads - Tipo de evento de correo', 'Prospección IA', NULL
    UNION ALL SELECT 603, '1', 'Enviado', NULL, NULL
    UNION ALL SELECT 603, '2', 'Respuesta', NULL, NULL
    UNION ALL SELECT 603, '3', 'Rebote', NULL, NULL
    UNION ALL SELECT 603, '4', 'Baja', NULL, NULL
    UNION ALL SELECT 603, '5', 'Apertura (aproximada)', NULL, NULL
    UNION ALL SELECT 603, '6', 'Pasó al vendedor', NULL, NULL
    -- 604: motivo de supresión (la_suppression.MT_REASONID)
    UNION ALL SELECT 604, '0', 'Leads - Motivo de supresión', 'Prospección IA', NULL
    UNION ALL SELECT 604, '1', 'Baja voluntaria', NULL, NULL
    UNION ALL SELECT 604, '2', 'Rebote duro', NULL, NULL
    UNION ALL SELECT 604, '3', 'Queja', NULL, NULL
    UNION ALL SELECT 604, '4', 'Exclusión manual', NULL, NULL
    -- 605: nivel del cargo (filtro person_seniorities[] de Apollo)
    UNION ALL SELECT 605, '0', 'Leads - Nivel del cargo', 'Prospección IA', NULL
    UNION ALL SELECT 605, '1', 'Dueño', NULL, 'owner'
    UNION ALL SELECT 605, '2', 'Fundador', NULL, 'founder'
    UNION ALL SELECT 605, '3', 'Alta dirección (C-level)', NULL, 'c_suite'
    UNION ALL SELECT 605, '4', 'Socio', NULL, 'partner'
    UNION ALL SELECT 605, '5', 'Vicepresidente', NULL, 'vp'
    UNION ALL SELECT 605, '6', 'Jefe de área', NULL, 'head'
    UNION ALL SELECT 605, '7', 'Director', NULL, 'director'
    UNION ALL SELECT 605, '8', 'Gerente', NULL, 'manager'
    UNION ALL SELECT 605, '9', 'Profesional sénior', NULL, 'senior'
    UNION ALL SELECT 605, '10', 'Nivel inicial', NULL, 'entry'
    UNION ALL SELECT 605, '11', 'Practicante', NULL, 'intern'
    -- 606: tamaño de empresa (filtro organization_num_employees_ranges[] de Apollo)
    UNION ALL SELECT 606, '0', 'Leads - Tamaño de empresa', 'Prospección IA', NULL
    UNION ALL SELECT 606, '1', '1 a 10 empleados', NULL, '1,10'
    UNION ALL SELECT 606, '2', '11 a 50 empleados', NULL, '11,50'
    UNION ALL SELECT 606, '3', '51 a 200 empleados', NULL, '51,200'
    UNION ALL SELECT 606, '4', '201 a 500 empleados', NULL, '201,500'
    UNION ALL SELECT 606, '5', '501 a 1.000 empleados', NULL, '501,1000'
    UNION ALL SELECT 606, '6', '1.001 a 5.000 empleados', NULL, '1001,5000'
    UNION ALL SELECT 606, '7', '5.001 a 10.000 empleados', NULL, '5001,10000'
    UNION ALL SELECT 606, '8', 'Más de 10.000 empleados', NULL, '10001,'
) c;

-- ---------------------------------------------------------------------
-- Etiquetas lbl* de las respuestas del servicio (MessageKeys). Las que ya existan en el tenant no se tocan.
-- ---------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_la_labels;
CREATE TEMPORARY TABLE tmp_la_labels (
    LBL   VARCHAR(100) NOT NULL,
    TEXTO VARCHAR(255) NOT NULL
);
INSERT INTO tmp_la_labels (LBL, TEXTO) VALUES
    ('lblSuccess', 'Operación realizada con éxito.'),
    ('lblValidationError', 'Revise los datos enviados.'),
    ('lblNotFound', 'No se encontró el registro.'),
    ('lblAccessDenied', 'No tiene permiso para realizar esta operación.'),
    ('lblExternalServiceError', 'Un servicio externo no respondió. Intente de nuevo en unos minutos.'),
    ('lblNotImplemented', 'Esta función aún no está disponible.'),
    ('lblUnexpectedError', 'Ocurrió un error inesperado. Intente de nuevo.'),
    ('lblUnauthorized', 'Su sesión no es válida o expiró. Vuelva a iniciar sesión.'),
    ('lblTenantNotAllowed', 'Prospección IA no está habilitada para esta empresa.'),
    ('lblUserNotFound', 'Su usuario no está registrado en el CRM.'),
    ('lblConflict', 'Otra persona cambió estos datos mientras usted los editaba. Recargue la página e intente de nuevo.'),
    ('lblProfileInvalid', 'El perfil de interés no tiene un formato JSON válido.'),
    ('lblPendingValidation', 'El perfil de interés o el mensaje base todavía tienen puntos marcados como [validar].'),
    ('lblProfileMissing', 'Configure el perfil de interés antes de calificar contactos.'),
    ('lblCreditLimit', 'Se alcanzó el tope de créditos de la campaña o del mes. La búsqueda se retoma cuando haya cupo.'),
    ('lblBaseMessageMissing', 'Configure el mensaje base antes de redactar correos.'),
    ('lblDraftAlreadyApproved', 'Este contacto ya tiene un correo aprobado. No se puede redactar otro.'),
    ('lblDraftNotValid', 'No se logró un borrador que cumpla las reglas del mensaje base. Intente de nuevo o revise el mensaje base.'),
    ('lblDraftNotPending', 'Este borrador ya fue aprobado, descartado o venció. Recargue la bandeja.'),
    ('lblContactWithoutEmail', 'El contacto no tiene un correo de trabajo verificado. No se puede enviar.'),
    ('lblContactSuppressed', 'Este contacto pidió no recibir más correos (o su correo rebotó). No se le puede escribir.'),
    ('lblSearchNotUnderstood', 'No se entendió a quién buscar. Escriba el cargo, el sector o la ciudad, por ejemplo: gerentes de TI de clínicas en Bogotá.'),
    ('lblSearchFiltersEmpty', 'Agregue al menos un cargo, un nivel, un sector o una palabra clave antes de buscar.'),
    ('lblConfigMissing', 'Falta la configuración de la prospección (perfil de interés, umbral y mensaje base).');

DELETE FROM tmp_la_labels
WHERE LBL IN (SELECT VALUE FROM sy_mastertable WHERE TABLECODE = 300);

SET @base_back = (SELECT COALESCE(MAX(CAST(CODE AS UNSIGNED)), 0) FROM sy_mastertable WHERE TABLECODE = 300);
SET @base_front = (SELECT COALESCE(MAX(CAST(CODE AS UNSIGNED)), 0) FROM sy_mastertable WHERE TABLECODE = 301);

DROP TEMPORARY TABLE IF EXISTS tmp_la_labels_codigos;
CREATE TEMPORARY TABLE tmp_la_labels_codigos AS
SELECT LBL, TEXTO,
       -- CAST a UNSIGNED: MySQL guarda las variables como DECIMAL y CAST(... AS CHAR) daría '1.000000...'.
       CAST(CAST(@base_back AS UNSIGNED) + ROW_NUMBER() OVER (ORDER BY LBL) AS CHAR) AS CODE_BACK,
       CAST(CAST(@base_front AS UNSIGNED) + ROW_NUMBER() OVER (ORDER BY LBL) AS CHAR) AS CODE_FRONT
FROM tmp_la_labels;

-- Clave (TABLECODE 300)
INSERT INTO sy_mastertable (TABLECODE, CODE, VALUE, OBSERVATION, LANGUAJEID, SHORTCUT, MODULE, STATUS, MAINTENANCEPERUSER, DEFAULTVAL)
SELECT 300, CODE_BACK, LBL, 'Prospección IA', @idioma_id, NULL, NULL, NULL, 0, 0 FROM tmp_la_labels_codigos;

-- Texto en español (TABLECODE 301)
INSERT INTO sy_mastertable (TABLECODE, CODE, VALUE, OBSERVATION, LANGUAJEID, SHORTCUT, MODULE, STATUS, MAINTENANCEPERUSER, DEFAULTVAL)
SELECT 301, CODE_FRONT, TEXTO, 'Prospección IA', @idioma_id, NULL, NULL, NULL, 0, 0 FROM tmp_la_labels_codigos;

-- Relación clave -> texto para el idioma
INSERT INTO sy_relationshiplabel (MT_LABELBACKID, MT_LABELFRONTID, MT_LANGUAGEID, CREATEDDATE, CREATEDBY)
SELECT CODE_BACK, CODE_FRONT, @idioma_id, @hoy_id, @usuario_id FROM tmp_la_labels_codigos;

DROP TEMPORARY TABLE tmp_la_labels_codigos;
DROP TEMPORARY TABLE tmp_la_labels;
