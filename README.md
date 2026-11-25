# msa-prospeccion-crm

Microservicio de **automatización de Leads ("Prospección IA")** de Sueco CRM.

> **Estado:** esqueleto. Hay estructura, configuración, entidades y firmas, pero **no hay lógica de negocio**.
> Los endpoints responden `501` con `messageKey = lblNotImplemented` hasta que se implementen.

## Qué hará

1. El usuario escribe una búsqueda y Gemini la convierte en filtros de Apollo.
2. Apollo busca gerentes y directores.
3. Se descartan bajas, clientes y prospectos que ya existen en el CRM.
4. Gemini califica cada contacto de 0 a 100.
5. Apollo o Hunter consiguen y verifican el correo.
6. El prospecto se crea en el CRM (`pm_leaflet`, `pm_leafletcontact`, `pm_leafletcontactmedium`) a través de `msa-cotizaciones`.
7. Gemini redacta el correo y una persona lo aprueba.
8. Se envía respetando la **Ley 2300** (L–V 7:00–19:00, sáb 8:00–15:00, hora de Colombia, sin domingos ni festivos).
9. Se leen respuestas, rebotes y bajas.
10. Si el contacto responde, se crea una tarea en el CRM (`pm_task` + `pm_taskleaflet`).

## Requisitos

Las versiones son las mismas que usan los repos del CRM (revisado el 2026-11-19 en sus `build.gradle` y `package.json`).

| Herramienta | Versión | Para qué |
|---|---|---|
| JDK | 21 (como msa-cotizaciones, params, security y gateway) | Este servicio |
| Gradle | No hace falta instalarlo: se usa el wrapper (`gradlew`, Gradle 8.14.6) | Este servicio |
| Spring Boot / Spring Cloud | 3.3.x / 2023.0.x (en `build.gradle`) | Este servicio |
| Docker Desktop | Con Docker Compose v2 o posterior | MySQL local |
| Node.js | 20 o 22 LTS. Angular 18 no admite Node 24; las imágenes del CRM usan Node 18 | `mf-cotizaciones` y `gtc-crm-root` |
| Angular CLI | 18.2: viene como dependencia de cada frontend (`npm ci --legacy-peer-deps` y `npm run start:local`); no hace falta instalarlo global | `mf-cotizaciones` y `gtc-crm-root` |
| Postman o Bruno | Cualquiera | Probar la API (colección en la tarea 2.12) |
| VS Code | Extensiones recomendadas en `.vscode/extensions.json` (Java, Spring Boot, Lombok, Gradle, Docker, Angular y EditorConfig) | |

### Ramas

| Repo | Rama de trabajo | Base |
|---|---|---|
| Este repo (`msa-prospeccion-crm`) | `dev`; `main` solo recibe PR | — |
| `mf-cotizaciones`, `gtc-crm-root`, `msa-apigateway-crm`, `msa-params-crm` | `feature/prospeccion-ia` | `feature/deployCrm` (la rama activa del CRM) |

Los PR usan la plantilla de [docs/desarrollo/plantilla-pull-request.md](docs/desarrollo/plantilla-pull-request.md).

## Puesta en marcha local

Comandos para PowerShell, desde la raíz del repositorio:

```powershell
# 1. Variables de entorno (valores falsos de ejemplo; el .env nunca se sube)
Copy-Item .env.example .env

# 2. MySQL 8 vacío (simula la base de un tenant). Puerto por defecto: 3308
docker compose -f docker-compose.local.yml up -d --wait

# 2b. Tablas (una sola vez): versión mínima de las tablas del CRM (solo local) y luego las tablas la_*
Get-Content tools\db-local\crm-stubs.sql | docker exec -i msa-prospeccion-crm-local-mysql-1 mysql -uroot -p"cambia-esta-clave-root" crm_tenant_local
Get-Content src\main\resources\db\scripts\V1__prospeccion.sql | docker exec -i msa-prospeccion-crm-local-mysql-1 mysql -uroot -p"cambia-esta-clave-root" crm_tenant_local
Get-Content -Encoding UTF8 src\main\resources\db\scripts\V2__catalogos_y_etiquetas.sql | docker exec -i msa-prospeccion-crm-local-mysql-1 mysql -uroot -p"cambia-esta-clave-root" --default-character-set=utf8mb4 crm_tenant_local

# 3. Compilar
.\gradlew.bat build -x test

# 4. Ejecutar con el perfil local
$env:SPRING_PROFILES_ACTIVE = "local"; .\gradlew.bat bootRun
```

También se puede arrancar desde VS Code con la configuración **"MsaProspeccionApplication (local)"** (`.vscode/launch.json`).

Comprobaciones con la app arriba (puerto `SERVER_PORT` del `.env`, 8099 en el ejemplo):

- `http://localhost:8099/msa-prospeccion/actuator/health`: `{"status":"UP"}`
- `http://localhost:8099/msa-prospeccion/swagger-ui.html`: documentación de la API
- Todo el servicio vive bajo `/msa-prospeccion` (como los demás servicios del CRM detrás del gateway).
- `/msa-prospeccion/api/**` sin token responde `401`; `/msa-prospeccion/public/unsubscribe?t=...` responde `501` (TODO)
- Con un token de prueba, `/msa-prospeccion/api/config` pasa la seguridad y responde `501` (servicio en TODO):

  ```powershell
  $token = python tools/token-local/generar_token.py
  curl.exe -H "Authorization: Bearer $token" http://localhost:8099/msa-prospeccion/api/config
  ```

  `generar_token.py` firma con la `JWT_SECRET_KEY` inventada del `.env` local. Con `--sin-tenant`, `--tenant otro`, `--minutos -5` o `--alg HS256` se prueban los rechazos y el token del login local (ver `--help`).

### Seguridad y tenant

| Caso | Respuesta |
|---|---|
| Sin token, token vencido o firmado con otra clave | `401` `lblUnauthorized` |
| Token sin `db_code`, `db_code` no habilitado en `PROSPECCION_TENANTS_ENABLED` o header `X-Tenant-Database-Code` distinto al token | `403` `lblTenantNotAllowed` |
| Token válido | El tenant sale de `db_code` y el usuario de `sub` (`CurrentUserProvider`: se busca como `sc_user.LOGIN` y, si es numérico, como `ID`) |

El JWT se valida con la misma clave HMAC del gateway (`JWT_SECRET_KEY`, HS256 o HS512). La base de cada tenant se abre la primera vez que se usa, con las credenciales de AWS SSM (`<AWS_SSM_TENANT_BASE_PATH><tenant>/url|username|password|driverclass`).

Para apagar MySQL: `docker compose -f docker-compose.local.yml down` (agrega `-v` para borrar los datos).

### Qué cambia en el perfil `local`

- Lee el archivo `.env` de la raíz (`spring.config.import`).
- Eureka desactivado (`eureka.client.enabled=false`).
- El tenant sale de variables de entorno (`prospeccion.local-tenant.*`) en lugar de AWS SSM.
- Jobs programados desactivados.
- En todos los perfiles, `spring.jpa.hibernate.ddl-auto=none`: Hibernate **nunca** crea ni altera tablas.

## Variables de entorno

Todas están en `.env.example` con valores falsos.

| Variable | Uso |
|---|---|
| `SERVER_PORT` | Puerto HTTP (por asignar) |
| `EUREKA_URL` | `defaultZone` de Eureka (qa/prod) |
| `JWT_SECRET_KEY` | Clave HMAC en Base64 con la que el CRM firma el JWT. En qa/prod, **la misma del gateway**; en local, una inventada |
| `AWS_REGION`, `AWS_SSM_TENANT_BASE_PATH` | Credenciales por tenant en SSM: `<base><tenant>/url\|username\|password\|driverclass`. Base del CRM: `/config/gtcproject/tenant_db/` |
| `TENANT_POOL_MAX_SIZE` | Opcional: conexiones máximas por tenant (5 por defecto) |
| `PROSPECCION_TENANTS_ENABLED` | Tenants donde corre el robot (separados por coma) |
| `PROSPECCION_JOBS_ENABLED` | Enciende los jobs (por defecto `false`) |
| `MAILBOX_PROVIDER` | `gmail`, `graph` o `none` (aún sin decidir) |
| `UNSUBSCRIBE_BASE_URL`, `UNSUBSCRIBE_TOKEN_SECRET_SSM_PARAMETER` | Enlace de baja y nombre del secreto de firma en SSM |
| `APOLLO_*`, `HUNTER_*`, `GEMINI_*` | URL base y **nombre del parámetro SSM** de cada llave (nunca la llave) |
| `GEMINI_MODEL` | Modelo de Gemini (`gemini-2.5-flash` por defecto). Se cambia sin tocar el código |
| `GEMINI_MAX_RETRIES`, `APOLLO_MAX_RETRIES` | Opcional: reintentos ante 429, 5xx o fallas de red (3 por defecto). En Apollo, un 429 con espera de más de 60 s no se reintenta: se devuelve `RateLimited` para reprogramar |
| `GEMINI_QUALIFICATION_BATCH_SIZE` | Opcional: contactos por llamada a Gemini al calificar (10 por defecto). Más alto = menos llamadas, pero un lote fallido se reintenta de a uno |
| `APOLLO_API_KEY`, `HUNTER_API_KEY`, `GEMINI_API_KEY` | **Solo en local** y con datos de prueba. En qa/prod van vacías y las llaves salen de SSM. Con `GEMINI_API_KEY` o `APOLLO_API_KEY` definidas como variables de entorno también corren `GeminiClientLiveTest` y `ApolloClientLiveTest`, las pruebas contra las APIs reales (Apollo: una página de 5 personas, 0 créditos) |
| `FEIGN_CONNECT_TIMEOUT_MS`, `FEIGN_READ_TIMEOUT_MS` | Opcionales (5000 / 15000 por defecto) |
| `MYSQL_*` | MySQL local de `docker-compose.local.yml` |
| `LOCAL_TENANT_CODE` | Código del tenant simulado en local |

## Estructura

```
src/main/java/co/com/gtc/prospeccion
├── config/         seguridad, OpenAPI, Feign, RestClient, propiedades
├── multitenancy/   TenantContext, DataSource por tenant, SSM, runner de jobs
├── security/       usuario actual y operaciones de Seguridad Interna
├── common/         GeneralReponse, excepciones, utilidades, constantes
├── crm/            tablas y servicios del CRM (Leads NO es dueño de estas tablas)
├── integration/    Apollo, Hunter, Gemini y puerto del buzón
├── search/ prospect/ draft/ sending/ tracking/ suppression/ credits/ tenantconfig/ dashboard/
└── jobs/           búsquedas programadas, envío, sincronización del buzón y mantenimiento
```

Capas: `Controller → Service (interfaz + impl) → DAO (interfaz + impl) → Repository`. Entidades con sufijo `ET`, MapStruct y Lombok. Todas las respuestas usan `GeneralReponse` con claves `lbl*`.

## Documentos de negocio

| Documento | Para qué |
|---|---|
| [docs/negocio/panel.md](docs/negocio/panel.md) | Panel: pendientes de hoy, embudo por cohorte, tasas del periodo y créditos del mes (tarea 6.1) |
| [docs/negocio/permisos.md](docs/negocio/permisos.md) | Permisos: las 4 operaciones (VER, EJECUTAR, APROBAR, CONFIGURAR), qué endpoint exige cada una, tabla de roles y conexión con Seguridad Interna (tarea 2.9) |
| [docs/negocio/seguimiento.md](docs/negocio/seguimiento.md) | Seguimiento: respuestas, rebotes, quejas, "fuera de la oficina" y pedidos de baja leídos del buzón; píxel de aperturas apagado (tarea 5.4) |
| [docs/negocio/perfil-de-interes.md](docs/negocio/perfil-de-interes.md) | Sectores, tamaños, ciudades, cargos y criterios de puntaje (`PROFILE_OF_INTEREST`) |
| [docs/negocio/correo-de-contacto.md](docs/negocio/correo-de-contacto.md) | Cómo se consigue y se verifica el correo de cada contacto calificado (Apollo y Hunter), cuándo se descarta y cuánto cuesta (tarea 4.1) |
| [docs/negocio/busqueda-y-campanas.md](docs/negocio/busqueda-y-campanas.md) | Búsqueda en lenguaje natural y campañas automáticas (diarias o semanales) con deduplicación contra el CRM: Apollo, exclusiones, calificación, correo, contacto y borrador (tareas 3.2 y 3.4) |
| [docs/negocio/bandeja-de-borradores.md](docs/negocio/bandeja-de-borradores.md) | Bandeja de borradores: revisar, editar como versión nueva, descartar con motivo y aprobar (tarea 5.1) |
| [docs/negocio/bajas.md](docs/negocio/bajas.md) | Bajas con enlace firmado: página pública de un clic, lista de bloqueo permanente y seguridad del token (tarea 5.5) |
| [docs/negocio/cola-de-envio.md](docs/negocio/cola-de-envio.md) | Cola de envío: horario de la Ley 2300, festivos, límite diario y espaciado entre correos (tarea 5.3) |
| [docs/negocio/paso-al-vendedor.md](docs/negocio/paso-al-vendedor.md) | Quien responde pasa al vendedor: tarea en el CRM sobre su prospecto, a nombre de quien aprobó el correo (tarea 5.6) |
| [docs/negocio/envio-de-correos.md](docs/negocio/envio-de-correos.md) | Envío: qué lleva cada correo (pie legal, baja en un clic, Message-ID), reintentos y cómo probarlo con Mailpit (tarea 5.2) |
| [docs/negocio/creditos.md](docs/negocio/creditos.md) | Topes de créditos de Apollo y Hunter por campaña y por mes, alertas y cómo se ajustan (tarea 4.2) |
| [docs/desarrollo/pantallas-en-el-crm.md](docs/desarrollo/pantallas-en-el-crm.md) | Pantallas de Prospección IA dentro del CRM: módulo en mf-cotizaciones, ruta en el shell, estilos v5 encapsulados, permisos y cómo probarlo (tarea 3.3) |
| [docs/desarrollo/estandar-de-interfaces.md](docs/desarrollo/estandar-de-interfaces.md) | Estándar de interfaces ES-GS-004 v5 aplicado a las pantallas del módulo (3.2, 5.1, 6.1, 6.2) y lo que falta decidir frente al CRM actual |
| [docs/negocio/mensaje-base.md](docs/negocio/mensaje-base.md) | Correo modelo de la empresa, reglas del borrador (`BASE_MESSAGE`), cómo las revisa el sistema al redactar (tarea 4.3) y propuesta de pie legal |

## Operación y pruebas previas

| Documento | Para qué |
|---|---|
| [docs/operacion/prerrequisitos-ti.md](docs/operacion/prerrequisitos-ti.md) | Lo que TI entrega: dominio secundario, buzones, DNS (SPF, DKIM, DMARC), permisos de API, facturación de Gemini y llaves en AWS SSM |
| [tools/prueba-cobertura/README.md](tools/prueba-cobertura/README.md) | Prueba de cobertura de Apollo y Hunter en Colombia (30 búsquedas, etapa sin créditos) |
| [tools/crm-local/README.md](tools/crm-local/README.md) | CRM completo en local (login, gateway, Eureka, params, frontends) con msa-prospeccion y una sola base MySQL. Se arranca con `tools\crm-local\iniciar-crm.ps1` |
| [tools/mini-front/README.md](tools/mini-front/README.md) | Mini front local para ver y probar el servicio: configuración del tenant (6.2) y prueba de calificación con contactos inventados (3.5). Se arranca con `tools\mini-front\iniciar.ps1` |
| [tools/bruno/README.md](tools/bruno/README.md) | Colección de Bruno: cada endpoint del servicio y llamadas de ejemplo a Apollo, Hunter y Gemini (token de prueba con `tools/token-local`) |
| [tools/entrega-crm/README.md](tools/entrega-crm/README.md) | Cómo editar los repos del CRM sin permiso de escritura y entregar los cambios en un ZIP al programador del CRM |
| [src/main/resources/db/scripts/V1__prospeccion.sql](src/main/resources/db/scripts/V1__prospeccion.sql) | Las 11 tablas `la_*` del modelo de datos v3.5 (se crean en la base de cada tenant). Fuera del diagrama: `la_tenantconfig.COMPANY_LINKEDIN_URL` y `la_draft.MODEL` / `PROMPT_VERSION` |
| [src/main/resources/db/scripts/V2__catalogos_y_etiquetas.sql](src/main/resources/db/scripts/V2__catalogos_y_etiquetas.sql) | Catálogos del módulo en `sy_mastertable` (TABLECODE 600 a 606: estados de búsqueda, borrador y envío, eventos, motivos de baja, niveles y tamaños) y etiquetas `lbl*` |
| [tools/db-local/crm-stubs.sql](tools/db-local/crm-stubs.sql) | Solo local: versión mínima de las tablas del CRM a las que apuntan las FK, hasta tener la estructura real (tarea 2.11) |

## Integración con el CRM

| Documento | Para qué |
|---|---|
| [docs/crm/peticion-completa-y-dominio-de-prospectos.md](docs/crm/peticion-completa-y-dominio-de-prospectos.md) | Recorrido de una petición (frontend → gateway → msa-cotizaciones → MySQL), tablas de prospectos y tareas, endpoints y catálogos que usa el módulo, y preguntas abiertas para el equipo CRM |

## Pendientes en este repo

- Toda la lógica de negocio: buscar `TODO` en el código.
- Confirmar con el equipo CRM los TABLECODE 600 a 606 de `V2__catalogos_y_etiquetas.sql` y los valores de `STATUS`/`MODULE` en `sy_mastertable`. Falta el código de las operaciones (`ProspeccionOperations`, tarea 2.9).
- Confirmar con el equipo CRM si `sub` del token de Sueco Auth es `sc_user.ID` o el login (el servicio ya admite ambos) y cómo llega `JWT_SECRET_KEY` al despliegue de qa/prod.
- Proveedor del buzón (Gmail API o Microsoft Graph) y su dependencia.
- Herramienta de migración (Flyway, Liquibase o scripts) para aplicar `V1__prospeccion.sql` en cada tenant, y confirmar con el equipo CRM los tipos de columna y los nombres de FK e índices propuestos.
- Bloqueo de jobs con varias instancias.

## Problemas conocidos

- ~~Lectura de AWS SSM rota por versiones de librerías~~ **Resuelto (2026-11-23):** el SDK de AWS 2.57 (`apache5-client`) necesita `httpclient5` 5.4 o más y Spring Boot 3.3 fijaba la 5.3.1 (`NoClassDefFoundError: TlsSocketStrategy`). `build.gradle` fija `httpclient5` 5.4.4 y `httpcore5` 5.3.4. Comprobado en local: el cliente de SSM se construye y una falla de AWS llega como error controlado.

## Pendientes fuera de este repo

- **mf-cotizaciones:** pantalla de Prospección IA y su `exposedModule` en la configuración de Module Federation.
- **gtc-crm-root:** entrada en `app.routes.ts` protegida con `dynamicMenuGuard`.
- **msa-apigateway-crm:** ruta `/msa-prospeccion/**` y exclusión de `/msa-prospeccion/public/unsubscribe` hechas en local (rama `feature/prospeccion-ia`, tarea 2.4); se entregan por ZIP con `tools/entrega-crm`.
- **Seguridad Interna:** crear las operaciones VER, EJECUTAR, APROBAR y CONFIGURAR y asignarlas a los roles.
- **sy_mastertable y etiquetas:** ejecutar `V2__catalogos_y_etiquetas.sql` en la base de cada tenant (después de `V1__prospeccion.sql`), con los TABLECODE confirmados.
- **AWS SSM:** llaves de Apollo, Hunter y Gemini, secreto del token de baja y credenciales del buzón.
- **Bases de los tenants:** script de creación de las tablas `la_*` en cada base.
