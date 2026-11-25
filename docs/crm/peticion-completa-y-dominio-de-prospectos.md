# Petición completa y dominio de prospectos en Sueco CRM

- **Tarea:** 1.6 (y respuestas de la 1.8 que se pudieron sacar del código)
- **Fecha:** 2026-11-18
- **Fuente:** código de `msa-apigateway-crm`, `msa-security-crm`, `msa-params-crm`, `msa-cotizaciones` y `mf-cotizaciones` (copias locales en `CRM COMPLETO/`). Todo lo que dice "confirmado" está en ese código; lo que dice "por confirmar" necesita al equipo CRM.

## 1. La petición completa, salto por salto

Ejemplo: un usuario crea un prospecto desde la pantalla de prospectos.

| # | Salto | Qué pasa | Tenant |
|---|---|---|---|
| 1 | Navegador → `gtc-crm-root` → `mf-cotizaciones` | El shell carga el microfrontend por Module Federation. `prospect.service.ts` llama `POST {APIEndpoint}leaflet/register-complete-prospect?userName=<login>`. `APIEndpoint` = `http://localhost:8090/msa-cotizaciones/api/` (local) o `https://crm-back.gtccorporation.net/msa-cotizaciones/api/` (prod). | El token va en `Authorization: Bearer` o en la cookie `session_token` |
| 2 | `msa-apigateway-crm` (puerto 8090) | `JwtAuthFilter` valida la firma HMAC del token. **Borra** cualquier `X-Tenant-Database-Code` que mande el cliente y lo vuelve a poner con el claim `db_code`. Enruta `/msa-cotizaciones/**` a `lb://MSA-COTIZACIONES` (Eureka). | Se fija aquí, desde el token |
| 3 | `msa-cotizaciones` (context-path `/msa-cotizaciones`) | `TenantInterceptorFilter` lee `X-Tenant-Database-Code` (si falta: 400) y lo pone en `TenantContext`. **No valida el JWT** (no tiene Spring Security). `LeafletController` → `LeafletServiceImpl.saveCompleteProspect`. | Header → `TenantContext` |
| 4 | `msa-cotizaciones` → `msa-params` y `msa-security` (Feign + Eureka) | Pide el ID de `sy_datetable` de hoy (`GET /msa-params/api/dateTable/{dateChar}`) y el usuario (`GET /msa-security/api/users/login/{login}`). `FeignClientInterceptor` reenvía `Authorization` y pone el tenant desde `TenantContext`. `FeignDecoder` desenvuelve el `GeneralReponse` y devuelve solo `data`. | `TenantContext` → header |
| 5 | `msa-cotizaciones` → MySQL del tenant | `DynamicTenantAwareRoutingDataSource` usa la conexión del tenant. Las credenciales salen de AWS SSM: `/config/gtcproject/tenant_db/<tenant>/url\|username\|password\|driverclass`. Inserta `pm_leaflet`, `pm_leafletcontact` y `pm_leafletcontactmedium` en una transacción. | Una base por tenant |

**Para `msa-prospeccion-crm`:** llamará a `msa-cotizaciones` directo por Eureka (salto 3 en adelante), sin pasar por el gateway. Por eso debe poner `X-Tenant-Database-Code` él mismo, desde `TenantContext` (ya lo hace `FeignGlobalConfig`), igual en peticiones y en jobs.

## 2. Dominio de prospectos

| Tabla | Qué es | Dueño |
|---|---|---|
| `pm_leaflet` | El prospecto (persona o empresa). `FIRSTNAME` es obligatorio; la identificación es opcional, pero si viene no se puede repetir con el mismo tipo. Estado en `MT_STATUSID` | msa-cotizaciones |
| `pm_leafletcontact` | Personas del prospecto. `USERFLG = 1`: contacto principal; `0`: persona de contacto adicional. `POSITION` = cargo | msa-cotizaciones |
| `pm_leafletcontactmedium` | Correo y teléfono de cada contacto (tipo en `sy_mastertable` 10) | msa-cotizaciones |
| `pm_listcontact` + enlace | Listas de contactos. `register-complete-prospect` asocia el contacto con `contactListIds` | msa-cotizaciones |
| `pm_task` | Tarea del vendedor (tipo, nombre, vencimiento, prioridad, estado) | msa-cotizaciones |
| `pm_taskleaflet` | Tarea ↔ prospecto. Se crea sola si `task/create` recibe `leafletId` | msa-cotizaciones |
| Historial (`/api/history-prospect`) | Solo lectura: historial de tareas y cotizaciones de un prospecto | msa-cotizaciones |
| `sy_mastertable` | Catálogos. `TABLECODE` numérico, `CODE` texto, una fila por idioma (`LANGUAJEID`) | msa-params |
| `sy_datetable` | Calendario: una fila por día. Las columnas de auditoría (`CREATEDDATE`…) guardan su `ID` | msa-params |
| `sc_user` | Usuarios (`ID`, `LOGIN`, `NAME`, `LASTNAME`, `EMAIL`…) | msa-security |

Ciclo de vida del prospecto (`TABLECODE 221`): `1` nuevo → `2` inactivo (60 días sin cotizaciones, tarea programada de msa-cotizaciones) → `3` cliente (`ProspectConversionService`).

## 3. Endpoints de msa-cotizaciones que usará Prospección IA

Todos bajo `/msa-cotizaciones/api`. Responden `GeneralReponse` del CRM: `{ success, errorsMessage, message, data }` (en este repo: `CrmResponseDTO`).

| Para qué | Método y ruta | Usuario | Cuerpo | `data` |
|---|---|---|---|---|
| Crear empresa + contacto + medios | `POST /leaflet/register-complete-prospect-by-user-id` | `?userId=` (sc_user.ID) | `FullProspectRequestDTO` | `LeafletCompleteProjection`: `id` = pm_leaflet, `contactId` = pm_leafletcontact |
| Agregar una persona a una empresa existente | `POST /leafletContact/personContact` | `?userName=` (login; no hay variante por ID) | `FullContactPersonRequestDTO` | `ContactDTO` (`id`) |
| Crear tarea asociada al prospecto (5.6) | `POST /task/create-by-user-id` | `?userId=` (queda a su nombre) | `RequestCreateTask` con `leafletId` | `ITaskProjection` (`id`). Responde 201 |

**No existen** (por eso se quitaron del puerto `CrmProspectPort`):

- Cambiar solo el estado de un prospecto. Lo más cercano es `PUT /leaflet/updateFullProspectByUserId/{id}`, que reemplaza todo el prospecto.
- Agregar un medio de contacto suelto. El frontend llama `POST contact-mediumLeaflet`, pero ese endpoint no está en el backend.
- Buscar prospectos por correo o por dominio. Solo hay búsqueda por documento (`GET /leaflet/document/{id}`).

### Mapeo propuesto de Apollo al CRM (por validar)

| Campo del CRM | Valor |
|---|---|
| `leaflet.firstName` | `organization.name` (razón social) |
| `leaflet.mtStatusId` | ID de `sy_mastertable` 221 / `"1"` (nuevo) |
| `leaflet.identificationNumber` | vacío (Apollo no da NIT; el vendedor lo completa si el prospecto responde) |
| `contact.userFlg` | por confirmar (ver preguntas) |
| `contact.name`, `contact.surname`, `contact.position` | `first_name`, `last_name`, `title` |
| `contactMediumList` | `[{ mtContactProviderETId: ID de 10/"5", value: correo verificado }]` |
| `contactListIds` | `[la_searchrun.PM_LISTCONTACTID]` si la búsqueda se asoció a una lista |

## 4. Catálogos confirmados (`sy_mastertable`)

| TABLECODE | Qué | CODE conocidos | Fuente |
|---|---|---|---|
| 10 | Tipo de medio de contacto | `1` teléfono, `5` correo | `ILeafletRepository`, `modal-contact.component.ts` |
| 35 | Tipo de identificación | — | `modal-contact.component.ts` |
| 221 | Estado del prospecto | `1` nuevo, `2` inactivo, `3` cliente | `LeafletServiceImpl`, `ProspectConversionService` |
| 246 | Tipo de tarea | por confirmar cuál usar | `ITaskRepository` |
| 247 | Prioridad de tarea | por confirmar | `ITaskRepository` |
| 248 | Periodicidad de tarea | por confirmar | `ITaskRepository` |
| 251 | Estado de tarea | `1` pendiente, `2` incompleta, `3` completada | `TaskServiceImpl` |

Los DTO llevan el **ID** de la fila, no el CODE: se resuelve con `MasterTableRepository.findFirstByTableCodeAndCode` (misma base del tenant) o con `GET /msa-params/api/masterTables/findByTableCodeAndCodeGreaterThanZeroAndCode/{tablecode}/{code}`.

## 5. Respuestas para la tarea 1.8 sacadas del código

| Pregunta | Respuesta | Fuente |
|---|---|---|
| ¿Cómo se firma el JWT? | **HMAC con clave simétrica en Base64**, no RS256/JWKS. El token de Sueco Auth usa HS512 (`JwtProvider`, `jwt.secret`); el del login local, HS256 (`JwtUtils`, `jwt.secret.key`). El gateway lo valida con `JWT_SECRET_KEY` | msa-security, msa-apigateway |
| ¿Qué claims trae? | `sub` (usuario), `db_code` (tenant), `roles`, `email`, `company_id`, `module_id`. El token del login local solo trae `sub` = login | `SuecoAuthTokenValidationService`, `application.properties` del gateway |
| ¿De dónde sale el tenant? | Del claim `db_code`. El gateway lo copia a `X-Tenant-Database-Code` y descarta el que mande el cliente | `JwtAuthFilter` |
| ¿Los servicios internos validan el token? | msa-cotizaciones y msa-params **no** (confían en el gateway y en el header). msa-security sí tiene resource server. **msa-prospeccion-crm sí lo valida** (tarea 2.3), con la misma clave HMAC del gateway | `build.gradle` de cada uno |
| ¿Ruta de las credenciales en SSM? | `/config/gtcproject/tenant_db/<tenant>/url\|username\|password\|driverclass` (password con descifrado) | `AwsSystemsManagerTenantConfigResolver` |
| ¿Columnas de `sy_mastertable`, `sy_datetable`, `sc_user`? | Ver sección 2 (mapeadas en `crm/entities`) | entidades de msa-cotizaciones |
| ¿DTO espejo de msa-cotizaciones? | Copiados en `crm/dto` | `quotes/dto/request` |
| ¿Rutas de msa-cotizaciones? | Sección 3 | controllers de msa-cotizaciones |

## 6. Qué se corrigió en este repo

- `CotizacionesFeignClient`: rutas con el prefijo real (`/msa-cotizaciones/api/...`), parámetro de usuario obligatorio y variantes por `userId`. Se quitó `taskleaflet/create` porque `task/create` ya crea la relación.
- DTO espejo con los campos reales y `CrmResponseDTO` para el sobre de respuesta del CRM (distinto del `GeneralReponse` de este servicio).
- `CrmProspectPort`: `createProspect` devuelve los dos IDs (empresa y contacto); se quitaron `createContactMedium` y `changeStatus`, que el CRM no ofrece.
- `FeignGlobalConfig`: el tenant sale de `TenantContext`, así funciona también desde los jobs.
- `MasterTableET.tableCode` pasa a numérico; `MasterTableCodes` trae los catálogos confirmados del CRM.
- `DateTableET`, `UserET`: columnas reales. `DateTableRepository.findByDate` busca por `DATE`.

## 7. Preguntas abiertas para el equipo CRM

1. **Formato de `sy_datetable.DATECHAR`.** msa-params y casi todo msa-cotizaciones lo buscan como `yyyyMMdd`, pero `saveCompleteProspect` y `updateCompleteProspect` usan `ddMMyyyy`. Uno de los dos no encuentra la fecha. Afecta directamente a `register-complete-prospect`.
2. **Empresa + gerente:** ¿el gerente de Apollo va como `userFlg = 1` (contacto principal) o `0` (persona de contacto)? ¿`firstName` del prospecto lleva la razón social?
3. **`sub` del token de Sueco Auth:** ¿es `sc_user.ID` o el login? (Desde la tarea 2.3 el servicio admite los dos: busca primero por `LOGIN` y, si es numérico, por `ID`.)
4. **Usuario para los jobs:** qué `sc_user` queda como `CREATEDBY` cuando el robot crea prospectos o tareas sin una persona detrás.
5. **Exclusión de prospectos existentes:** no hay búsqueda por correo ni dominio. Opciones: que msa-cotizaciones la agregue, o que este servicio consulte en solo lectura `pm_leafletcontactmedium` en la base del tenant (ya comparte la base).
6. **Tarea cuando alguien responde (5.6):** qué CODE de tipo (246), prioridad (247) y periodicidad (248) usar. Hoy son
   configurables (`HANDOFF_*`, tipo `1` por defecto). Ya resuelto en el código de msa-cotizaciones: `expirationDate`
   va como `yyyy-MM-dd` (también acepta `d/M/yyyy`) y `expirationHour` como `HH:mm`; la tarea queda a nombre del
   `userId` (no hay campo de asignado aparte).
7. **Canal de adquisición:** `TABLECODE` de `acquisitionChannel` y si se crea un valor "Prospección IA".
8. **Llamadas desde jobs a msa-security:** ese servicio sí valida el token; definir un token de servicio o evitar llamarlo.
