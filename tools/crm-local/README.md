# CRM completo en local

Levanta el CRM de verdad (login, gateway, Eureka, params y los dos frontends) junto con `msa-prospeccion`, todo contra la misma base MySQL local. **Solo desarrollo.**

```
Navegador (4200) -> gtc-crm-root + mf-cotizaciones (4201)
                 -> Gateway (8090) -> msa-security (8089)   login local con usuario y clave
                                   -> msa-params (8088)     etiquetas y catálogos
                                   -> msa-cotizaciones (8085) cotizaciones, prospectos y tareas
                                   -> msa-prospeccion (8099)
                    Eureka (8761) registra a todos; MySQL (Docker, 3308) es la base de todos.
```

## Cómo funciona el login local

- `msa-security-crm` con `SECURITY_AUTH_MODE=local` valida usuario y clave (BCrypt) en `sc_user` y entrega un JWT HS256 firmado con `JWT_SECRET_KEY`.
- El token local **no trae `db_code`** (regla del CRM: "ningún db_code simulado en tokens locales") y el gateway corre con `GATEWAY_TENANT_MODE=disabled`.
- `msa-prospeccion` en el perfil `local` usa entonces el tenant fijo `LOCAL_TENANT_CODE`. En qa/prod sigue exigiendo `db_code`.
- `sub` es el login (`admin.local`); el servicio lo busca en `sc_user.LOGIN`.

## Primera vez

1. **Docker Desktop** abierto y la base local con sus tablas (pasos 2 y 2b del [README principal](../../README.md)).
2. **Frontends** (necesita internet, unos minutos cada uno):
   ```powershell
   cd "..\mf-cotizaciones"; npm.cmd ci --legacy-peer-deps
   cd "..\gtc-crm-root";   npm.cmd ci --legacy-peer-deps
   ```
3. **Tablas del CRM y usuario** (desde la raíz de este repo):
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\crm-local\iniciar-crm.ps1 -CrearTablas -SinFront
   Get-Content tools\crm-local\usuario-local.sql | docker exec -i msa-prospeccion-crm-local-mysql-1 mysql -uroot -p"cambia-esta-clave-root" crm_tenant_local
   ```
   `-CrearTablas` hace que `msa-security`, `msa-params` y `msa-cotizaciones` creen sus tablas en la base local (Hibernate `update`). Después cierre todas las ventanas que se abrieron.

## Cada vez

Orden de arranque: Eureka (discovery, donde se registran todos), gateway, security, params, msa-cotizaciones, msa-prospeccion, mf-cotizaciones y root. Cada servicio espera a que el anterior esté arriba. Al final se abre `http://localhost:4200`; el root entra solo con el usuario de `gtc-crm-root\.local\local-auth.json` (se crea si falta).

### Opción 1 (recomendada): terminales dentro de VS Code

1. `Ctrl + Shift + P` → **Tasks: Run Task** → **CRM completo**.
2. En el panel de abajo aparece una terminal por servicio (a la derecha, la lista con los nombres). Cada una espera a que la anterior diga `Started ...` (Spring) o `Compiled successfully` (Angular).
3. Para apagar: **Tasks: Run Task** → **CRM: detener todo** (o la papelera de cada terminal).

También se puede arrancar un solo servicio: **Tasks: Run Task** → por ejemplo **CRM: msa-prospeccion (8099)**.

Solo Prospección IA, sin el resto del CRM: **Tasks: Run Task** → **Prospección IA: mini front** (MySQL, Gemini simulado,
`msa-prospeccion` y el [mini front](../mini-front/README.md) en http://localhost:4300). `msa-prospeccion` se registra
en Eureka solo si Eureka está corriendo, y usa el Gemini simulado (8771) mientras `GEMINI_API_KEY` esté vacía.

Base de desarrollo: con `SPRING_DATASOURCE_URL` en el `.env`, cada servicio usa su usuario (`DB_USER_SECURITY` /
`DB_PASS_SECURITY`, `DB_USER_PARAMS` / `DB_PASS_PARAMS`, `DB_USER_QUOTE` / `DB_PASS_QUOTE`) o, si no está,
`SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`.

### Opción 2: una ventana de PowerShell por servicio

```powershell
powershell -ExecutionPolicy Bypass -File tools\crm-local\iniciar-crm.ps1             # arrancar
powershell -ExecutionPolicy Bypass -File tools\crm-local\iniciar-crm.ps1 -Reiniciar  # cerrar todo y arrancar de cero
```

Para apagar: cierre las ventanas. MySQL (las dos opciones): `docker compose -f docker-compose.local.yml down`.

### Base local o de desarrollo

| Si el `.env`... | security, params y cotizaciones usan | Usuario para entrar |
|---|---|---|
| no tiene `SPRING_DATASOURCE_URL` | la MySQL local de Docker (vacía, solo estructura) | `admin.local` / `Local2026*` |
| tiene `SPRING_DATASOURCE_URL` de la base de desarrollo | la base de desarrollo del CRM (datos reales) | su usuario de desarrollo, en `local-auth.json` |

`msa-prospeccion` siempre usa la base local. Con la base de desarrollo, `-CrearTablas` está bloqueado y el correo sigue siendo de prueba (no salen correos reales). La configuración común de los dos modos está en `comun.ps1`.

## Comprobaciones

| URL | Qué debe responder |
|---|---|
| http://localhost:8761 | Eureka con MSA-SECURITY, MSA-PARAMS, MSA-APIGATEWAY y MSA-PROSPECCION-CRM |
| `POST http://localhost:8090/msa-security/login` con `{"login":"admin.local","password":"Local2026*"}` | `Autenticacion Correcta` y un `token` |
| `GET http://localhost:8090/msa-prospeccion/api/config` con `Authorization: Bearer <token>` | 200 con la configuración vigente |

## Problemas comunes

| Lo que pasa | Solución |
|---|---|
| "Falta instalar mf-cotizaciones / gtc-crm-root" | Paso 2 de "Primera vez" (con internet) |
| `Usuario no existe` o `Usuario bloqueado` | Ejecute otra vez `usuario-local.sql` (también desbloquea) |
| El gateway responde 503 en `/msa-prospeccion` | Eureka tarda unos segundos en ver el servicio: espere y recargue |
| Una ventana se cierra o muestra un error de tablas | Falta el paso 3 de "Primera vez" (`-CrearTablas`) |
| Puerto ocupado | Ya estaba corriendo en otra ventana: ciérrela |

## Qué falta

- La pantalla de Prospección IA dentro del CRM (`mf-cotizaciones`, tareas 3.2 y 6.2). Mientras tanto, el [mini front](../mini-front/README.md) sirve para probar el servicio.
- `msa-projects` no se levanta: sus pantallas no funcionarán en local.
- `msa-cotizaciones` corre con correo, FTP y datos de empresa de prueba y sin jobs programados: enviar correos o subir archivos fallará en local.
- Menús y permisos: el root usa `authorizationMode: 'mock'` (perfil `admin`).
