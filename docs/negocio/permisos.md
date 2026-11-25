# Permisos del módulo (tarea 2.9)

- **Estado:** v1, implementada y probada. Las operaciones salen de una tabla de roles configurable. Se conecta al
  permission-service de Sueco cuando la 1.13 entregue el ítem de menú.
- **Última actualización:** 2026-11-24

El backend valida cada acción, no solo el menú del front: quien no tiene la operación recibe **403**
(`lblAccessDenied`), aunque llame al endpoint directo.

## Las cuatro operaciones

| Operación | Qué permite | Endpoints |
|---|---|---|
| **VER** | Panel, bandeja, búsquedas y su historial, cola de envío, créditos, lista de bloqueo y configuración con su historial de versiones (solo lectura) | Todos los `GET` |
| **EJECUTAR** | Interpretar, previsualizar y ejecutar búsquedas; activar, pausar o cambiar la frecuencia de campañas. **Gasta créditos** | `POST /api/search/interpret`, `/preview`, `/run`, `PATCH /api/search/runs/{id}/schedule` |
| **APROBAR** | Editar, aprobar, descartar y redactar de nuevo borradores. **Aprobar envía el correo** | `PUT /api/drafts/{id}`, `POST /api/drafts/{id}/approve`, `/discard`, `/contacts/{id}/generate` |
| **CONFIGURAR** | Cambiar el perfil de interés, el mensaje base, el umbral y los límites; probar calificación y redacción | `PUT /api/config`, `POST /api/qualification/preview`, `POST /api/drafts/preview` y las herramientas locales |

Cualquier operación incluye **VER**: quien aprueba o configura también necesita ver. La página pública de baja (5.5)
no lleva JWT: la protege su enlace firmado.

## De dónde salen

1. El token de sueco-auth trae los roles del usuario (claim `roles`).
2. **`ROLE_SUPERADMIN` tiene todas las operaciones**, igual que en el front (`PermissionService.isSuperAdmin`).
3. Para los demás roles, la tabla `prospeccion.security.operations-by-role`. **Un rol que no está en la tabla no tiene
   ninguna operación** (se niega por defecto). Una operación mal escrita detiene el arranque del servicio, para que
   un error de escritura no dé ni quite permisos en silencio.

En QA y producción la tabla se llena por variables de entorno, un rol por variable:

```
PROSPECCION_SECURITY_OPERATIONSBYROLE_COMERCIAL=VER,APROBAR
PROSPECCION_SECURITY_OPERATIONSBYROLE_JEFECOMERCIAL=VER,EJECUTAR,APROBAR,CONFIGURAR
```

En local (`application-local.yml`): `ADMINISTRADOR` tiene las cuatro y `COMERCIAL` tiene VER y APROBAR, para probar un
rol limitado (`python tools/token-local/generar_token.py --roles COMERCIAL`).

## Con la 1.13 (Seguridad Interna)

El front de Sueco ya no lee permisos del token: los pide al **permission-service** (`GET /api/menu/module`), que
devuelve cada ítem de menú con su `assignmentId` y sus acciones (Abrir, Crear, Editar, Eliminar, Exportar, Imprimir).
Propuesta para el administrador de Sueco:

| Ítem de menú | Acción en Sueco | Operación del módulo |
|---|---|---|
| Prospección IA | Abrir | VER |
| Prospección IA | Crear | EJECUTAR |
| Prospección IA | Editar | APROBAR |
| Configuración de Prospección (ruta `/home/prospeccion-ia/configuracion`) | Editar | CONFIGURAR |

Cuando exista el ítem, `OperationsAuthoritiesConverter` puede leer esas acciones del permission-service en lugar de
la tabla. Los endpoints no cambian: siguen exigiendo VER, EJECUTAR, APROBAR o CONFIGURAR.

## Cómo se prueba

- `EndpointPermissionsTest` falla si alguien agrega un endpoint sin `@PreAuthorize`, y revisa que buscar, aprobar,
  descartar y configurar no sean de solo lectura.
- Prueba contra el servicio local (2026-11-24), mismo endpoint con tokens de distintos roles:

| Caso | ADMINISTRADOR | COMERCIAL | Rol fuera de la tabla | ROLE_SUPERADMIN |
|---|---|---|---|---|
| Ver panel o configuración | 200 | 200 | 403 | 200 |
| Interpretar búsqueda | 200 | 403 | 403 | 200 |
| Aprobar borrador | permitido | permitido | 403 | permitido |
| Probar calificación / herramientas locales | 200 | 403 | 403 | 200 |
| Sin token | 401 | | | |

## Pendiente

- Ítem de menú y acciones en Seguridad Interna (1.13) y conectar el permission-service.
- Los roles reales de cada vendedor en QA: llenar la tabla por variables de entorno hasta entonces.
- QA lo prueba en la 7.2 ("operaciones por rol").
