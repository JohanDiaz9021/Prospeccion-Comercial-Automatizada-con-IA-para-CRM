# Panel (tarea 6.1)

- **Estado:** v1, implementado y probado contra la base local. Envíos, respuestas y rebotes ya cuentan con el envío
  (5.2) y la lectura del buzón (5.4); en local, por Mailpit.
- **Última actualización:** 2026-11-23

Una sola pantalla para saber cómo va la prospección: qué espera hoy a una persona, cuánto rindieron las búsquedas del
periodo y cuánto se ha gastado en créditos.

## Qué muestra

### Hoy (no depende del periodo)

| Tarjeta | Qué cuenta |
|---|---|
| Por revisar | Borradores esperando aprobación (la última versión de cada contacto), de cualquier origen. Es el único paso humano |
| En cola de envío | Correos aprobados que esperan su turno y la hora del próximo, en hora de Colombia |
| Campañas activas | Búsquedas que se repiten cada día hábil, y la última búsqueda con su estado |

### Embudo del periodo

Por defecto el periodo es el **mes actual** (hora de Colombia), y se puede filtrar por una búsqueda o campaña. Una
campaña incluye sus ejecuciones automáticas.

El embudo es **por cohorte**: toma las búsquedas creadas en el periodo y sigue a sus contactos hasta hoy. Así cada paso
sale del anterior y las tasas no mezclan contactos de meses distintos. Un borrador aprobado en noviembre de una
búsqueda de octubre cuenta en el embudo de octubre.

| Paso | De dónde sale |
|---|---|
| Personas nuevas | `la_searchrun.NEW_COUNT` de las búsquedas del periodo |
| Calificadas por la IA | `la_searchrun.QUALIFIED_COUNT` |
| Contactos creados | `la_apolloseen` con resultado CREADO |
| Con borrador | Contactos con al menos una versión en `la_draft` |
| Aprobados | Contactos cuya última versión está Aprobada |
| Enviados | `la_sendqueue` en estado Enviado |
| Respondieron | Envíos con un evento Respuesta en `la_emailevent` |
| Pasaron al Vendedor | Envíos con el evento "Pasó al vendedor": respondieron y ya tienen su tarea en el CRM (5.6, [paso-al-vendedor.md](paso-al-vendedor.md)) |

Debajo del embudo está lo que se quedó en el camino: descartadas por la IA, sin correo, excluidas, borradores por
revisar o descartados, en cola, fallidos o cancelados, rebotes y bajas.

Los borradores de prueba `[DEMO]` no cuentan en el embudo porque no vienen de una búsqueda, pero sí en "Por revisar".

### Tasas del periodo

| Tasa | Cálculo | Alerta |
|---|---|---|
| Calificación | Calificadas / personas nuevas | — |
| Aprobación | Aprobados / con borrador | — |
| Respuesta | Respondieron / enviados | — |
| Rebote | Envíos con rebote / enviados | "Revisar" sobre 2 %, "Alta" sobre 5 % |
| Baja | Envíos con baja / enviados | "Revisar" sobre 1 %, "Alta" sobre 3 % |

Sin base (por ejemplo, nada enviado todavía) la tasa sale vacía ("—"), no en 0 %. Los umbrales de rebote y baja son
una propuesta de entregabilidad para revisar con el equipo comercial.

### Créditos del mes

Las mismas tarjetas de la pestaña Créditos (4.2): consumo de Apollo y Hunter frente al presupuesto y saldo del
proveedor. Se cargan aparte para que el saldo de Hunter, si tarda, no frene el resto del panel.

## Endpoint

`GET /msa-prospeccion/api/dashboard?from=AAAA-MM-DD&to=AAAA-MM-DD&searchRunId=N` (todos opcionales). Responde 400 si
la fecha final es anterior a la inicial o si el periodo pasa de un año. Los créditos siguen en
`GET /msa-prospeccion/api/credits`.

## Pendiente

- Envíos, respuestas y rebotes del buzón real cuando TI defina el proveedor (1.11).
- Revisar con el equipo comercial los umbrales de alerta de rebote y baja.
- La pantalla real dentro del CRM (con la 3.3) y los permisos por rol (2.9).
