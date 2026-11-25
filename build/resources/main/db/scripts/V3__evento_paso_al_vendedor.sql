-- =====================================================================
-- msa-prospeccion-crm - tarea 5.6: evento "Pasó al vendedor" (sy_mastertable 603 / CODE '6').
-- Solo para bases donde ya se ejecutó V2__catalogos_y_etiquetas.sql antes de la 5.6 (las nuevas ya lo traen en V2).
-- Se puede ejecutar más de una vez: si el valor ya existe, no hace nada. MySQL 8.
-- =====================================================================

-- Mismo idioma que el resto del catálogo 603.
SET @idioma_id = (SELECT LANGUAJEID FROM sy_mastertable WHERE TABLECODE = 603 AND CODE = '0' LIMIT 1);

INSERT INTO sy_mastertable (TABLECODE, CODE, VALUE, OBSERVATION, LANGUAJEID, SHORTCUT, MODULE, STATUS, MAINTENANCEPERUSER, DEFAULTVAL)
SELECT 603, '6', 'Pasó al vendedor', NULL, @idioma_id, NULL, NULL, NULL, 0, 0
FROM DUAL
WHERE EXISTS (SELECT 1 FROM sy_mastertable WHERE TABLECODE = 603 AND CODE = '0')
  AND NOT EXISTS (SELECT 1 FROM sy_mastertable WHERE TABLECODE = 603 AND CODE = '6');

-- Debe mostrar una fila. Si no muestra ninguna, falta ejecutar V2 primero.
SELECT ID, TABLECODE, CODE, VALUE FROM sy_mastertable WHERE TABLECODE = 603 AND CODE = '6';
