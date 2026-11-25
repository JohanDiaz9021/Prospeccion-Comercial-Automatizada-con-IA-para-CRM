-- =====================================================================
-- SOLO LOCAL. Usuario para entrar al CRM completo en local (msa-security-crm con SECURITY_AUTH_MODE=local).
--   Usuario: admin.local    Clave: Local2026*
-- Requisitos: tools/db-local/crm-stubs.sql y haber creado las tablas del CRM (iniciar-crm.ps1 -CrearTablas).
-- Se puede ejecutar varias veces. Nunca usar en qa/prod.
-- =====================================================================

-- Perfil de seguridad: intentos fallidos y días para cambiar la clave (los usa el login local).
INSERT INTO sc_profile (ID, NAME, MINIMUMLENGTHPASSWORD, MAXIMUMLENGTHPASSWORD, AMOUNTFAILEDATTEMPSTOBLOCKUSER,
                        AMOUNTDAYSCHANGEPASSWORD, AMOUNTDAYSINACTIVITYBLOCKUSER, AMOUNTPASSWORDSTOKEEP)
VALUES (1, 'Administrador local', 8, 30, 10, 3650, 3650, 0)
ON DUPLICATE KEY UPDATE AMOUNTFAILEDATTEMPSTOBLOCKUSER = 10, AMOUNTDAYSCHANGEPASSWORD = 3650;

INSERT INTO sc_role (ID, NAME, SC_PROFILEID, ENABLED)
VALUES (1, 'ADMINISTRADOR', 1, 'true')
ON DUPLICATE KEY UPDATE SC_PROFILEID = 1, ENABLED = 'true';

-- Clave Local2026* cifrada con BCrypt (BCryptPasswordEncoder de Spring, el mismo de msa-security-crm).
UPDATE sc_user
SET PASSWORD = '$2a$10$f/6J55CXkXHoHycByMcHJu6ncDBbbHExay/sq4Re1s3SdXWj6L59S',
    SC_ROLEID = 1,
    NAME = COALESCE(NAME, 'Administrador'),
    LASTNAME = COALESCE(LASTNAME, 'Local'),
    ENABLED = 'true',
    ACCOUNTLOCKED = 'false',
    AUTHETICATIONATTEMPTS = 0,
    CHANGEPASSWORD = 'false',
    LASTCHANGEPASSWORDDATE = NOW()
WHERE LOGIN = 'admin.local';

SELECT u.ID, u.LOGIN, r.NAME AS ROL, u.ENABLED, u.ACCOUNTLOCKED
FROM sc_user u LEFT JOIN sc_role r ON r.ID = u.SC_ROLEID
WHERE u.LOGIN = 'admin.local';
