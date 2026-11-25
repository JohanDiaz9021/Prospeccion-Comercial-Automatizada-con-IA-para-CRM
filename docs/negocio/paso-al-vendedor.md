# Quien responde pasa al vendedor (tarea 5.6)

- **Estado:** implementado y probado con pruebas automáticas. Falta probarlo en vivo contra la base local y confirmar
  con el equipo CRM el tipo de tarea que se usa (ver "Por confirmar").
- **Última actualización:** 2026-11-25

Cuando alguien responde un correo de prospección, el sistema le crea al vendedor una **tarea en el CRM** sobre el
prospecto de esa persona. Así entra a sus tareas, al historial del prospecto, al embudo y a Oportunidades, sin un
servicio de notificaciones aparte. Nadie tiene que hacer nada: es automático.

## Cómo funciona

1. La búsqueda (3.2) ya creó en el CRM el prospecto (`pm_leaflet`) y la persona (`pm_leafletcontact`) con su correo.
2. El seguimiento (5.4) lee el buzón y registra la **respuesta**. Las respuestas automáticas ("fuera de la oficina"),
   los rebotes y los pedidos de baja **no** pasan al vendedor.
3. Después de cada lectura del buzón, el sistema toma las respuestas que aún no pasaron y crea la tarea en
   msa-cotizaciones (`POST /task/create-by-user-id`, con `leafletId`). msa-cotizaciones crea `pm_task` y la une al
   prospecto (`pm_taskleaflet`), así sale en el historial (`/api/history-prospect`).
4. Queda el evento **"Pasó al vendedor"** (catálogo 603, CODE 6) y el Panel lo cuenta como el último paso del embudo.

| Dato de la tarea | Valor |
|---|---|
| A nombre de | **Quien aprobó el correo** (5.1): es el vendedor que lo envió |
| Nombre | "Respondió a Prospección IA: Ana · Clínica Demo" |
| Observación | Cuándo respondió (hora de Colombia), el asunto del correo, el cargo y la empresa. El texto de la respuesta queda en el buzón |
| Vence | El siguiente día hábil (sin sábados, domingos ni festivos) a las 10:00 |
| Estado | Pendiente (lo pone msa-cotizaciones) |
| Tipo, prioridad y periodicidad | Configurables (`HANDOFF_*`) |

**Sin duplicados ni pérdidas:**
- **Si el CRM no responde**, la respuesta queda pendiente y se reintenta en la siguiente lectura del buzón (cada 15 minutos).
- **Una respuesta pasa una sola vez**, porque queda el evento "Pasó al vendedor".
- **Si a esa persona ya le creamos la tarea**, una segunda respuesta al mismo correo no crea otra.

El prospecto conserva su estado del CRM ("nuevo"). msa-cotizaciones no tiene un endpoint para cambiar solo el estado,
y la tarea pendiente es lo que el vendedor ve y trabaja.

## Probarlo en local

1. Ejecutar [V3__evento_paso_al_vendedor.sql](../../src/main/resources/db/scripts/V3__evento_paso_al_vendedor.sql) en
   la base local (si ya tenía el V2).
2. Aprobar un borrador y enviarlo (Cola de Envío → Enviar Ya, con Mailpit) o marcarlo como enviado.
3. En la Bandeja, abrir el aprobado y simular **Respuesta**. El aviso dice si pasó al vendedor.
4. La tarea queda en las tablas `pm_task` y `pm_taskleaflet` de la base local. En local no se llama a msa-cotizaciones:
   el adaptador local escribe lo mismo que él.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `HANDOFF_ENABLED` | `true` | Apagarlo sin desplegar |
| `HANDOFF_TASK_TYPE_CODE` | `1` | CODE del tipo de tarea (catálogo 246 del CRM). Obligatorio |
| `HANDOFF_PRIORITY_CODE` | vacío | CODE de prioridad (247). Vacío = sin prioridad |
| `HANDOFF_PERIODICITY_CODE` | vacío | CODE de periodicidad (248). Vacío = sin periodicidad |
| `HANDOFF_DUE_BUSINESS_DAYS` | 1 | Días hábiles para contactar a quien respondió |
| `HANDOFF_DUE_HOUR` | 10:00 | Hora de vencimiento |

Si el tipo de tarea no existe en el catálogo del tenant, no se crea ninguna tarea. Las respuestas siguen pendientes y el
log dice qué variable corregir.

## Por confirmar con el equipo CRM

- Qué CODE de tipo (246), prioridad (247) y periodicidad (248) usar. Pregunta 6 de
  [peticion-completa-y-dominio-de-prospectos.md](../crm/peticion-completa-y-dominio-de-prospectos.md). El formato de
  la fecha y la hora ya está resuelto: `yyyy-MM-dd` y `HH:mm`.
- Si el vendedor debe ser quien aprobó el correo (hoy) o el dueño de la campaña.
- Si quieren un estado propio del prospecto para "respondió". Necesitaría un endpoint en msa-cotizaciones.
