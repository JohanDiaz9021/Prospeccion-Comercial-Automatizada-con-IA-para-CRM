# Bandeja de borradores (tarea 5.1)

- **Estado:** v1, implementada y probada contra la base local.
- **Última actualización:** 2026-11-23

**Ningún correo sale sin que una persona lo apruebe.** Cada contacto con correo recibe un borrador que redacta Gemini
(4.3). La bandeja muestra la última versión de cada contacto para que alguien la revise.

## Qué se puede hacer

| Acción | Qué pasa |
|---|---|
| **Revisar** | Se ve el contacto (de su evidencia), el asunto, el correo como lo recibirá, el texto plano, las palabras y los avisos de las reglas (asunto largo, números que no vienen en los datos, firma que falta...) |
| **Editar** | No cambia la versión: crea la **siguiente** (marcada "editada por una persona") y la anterior queda en el historial. El HTML se limpia al guardar. Las marcas `{{...}}` o `[[...]]` sin llenar no se dejan guardar |
| **Redactar de nuevo** | Gemini escribe otra versión con el mensaje base vigente |
| **Descartar** | Pide el **motivo** (se guarda en `la_draft.DISCARD_REASON`). Sirve para mejorar el mensaje base y el perfil |
| **Aprobar** | Marca quién y cuándo aprobó y, **en la misma transacción**, pone el correo en la cola de envío con su turno (5.3). Si el encolado falla, el borrador no queda aprobado |

## Reglas

- Solo se edita, descarta o aprueba la **última versión** y mientras esté **"Por revisar"**.
- Si alguien guardó una versión más nueva mientras otra persona revisaba, la segunda recibe un **conflicto (409)**: lo que escribió no se pierde en pantalla y debe recargar.
- Un borrador aprobado, descartado o vencido es **solo lectura** (`lblDraftNotPending`).
- Si dos personas aprueban a la vez, la segunda recibe 409: la cola no admite el mismo borrador dos veces.

## Estados (`sy_mastertable` 601)

| Código | Estado |
|---|---|
| 1 | Por revisar |
| 2 | Aprobado (en la cola de envío) |
| 3 | Descartado (con motivo) |
| 4 | Vencido (regla de vencimiento por definir) |

## Endpoints

| Para qué | Ruta |
|---|---|
| Bandeja (última versión de cada contacto) | `GET /msa-prospeccion/api/drafts?status=1&page=0&size=20` |
| Detalle | `GET /msa-prospeccion/api/drafts/{id}` |
| Editar (crea la versión siguiente) | `PUT /msa-prospeccion/api/drafts/{id}` con `subject`, `body` y `version` |
| Aprobar y encolar | `POST /msa-prospeccion/api/drafts/{id}/approve` |
| Descartar | `POST /msa-prospeccion/api/drafts/{id}/discard` con `reason` |
| Historial | `GET /msa-prospeccion/api/drafts/contacts/{contactId}/versions` |
| Redactar de nuevo | `POST /msa-prospeccion/api/drafts/contacts/{contactId}/generate` |

En el mini front se ve en la pestaña **Bandeja de Borradores**. Mientras no exista la búsqueda (3.2), el botón
"Nuevo Borrador de Prueba" crea un contacto inventado `[DEMO]` en la base local y "Borrar Datos de Prueba" lo quita.

## Pendiente

- Permisos: quién puede ver y quién puede aprobar (2.9, espera la 1.13).
- Regla de vencimiento de los borradores sin revisar (por definir con el equipo comercial).
- La pantalla real dentro del CRM (3.3) con el estándar de interfaces.
