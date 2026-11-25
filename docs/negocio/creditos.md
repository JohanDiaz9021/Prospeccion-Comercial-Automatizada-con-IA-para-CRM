# Control de créditos (tarea 4.2)

- **Estado:** v1, implementado y probado contra la base local.
- **Última actualización:** 2026-11-23

Apollo y Hunter cobran por cada correo que se consigue (tarea 4.1). El control de créditos evita que una sola campaña se gaste el plan del mes, y avisa antes de que se acabe.

## Qué se cobra

| Proveedor | Operación | Créditos |
|---|---|---|
| Apollo | People Enrichment que entrega el correo (`PEOPLE_ENRICHMENT`) | 1 |
| Hunter | Email Finder que encuentra el correo (`EMAIL_FINDER`) | 1 (si no lo encuentra, 0) |
| Hunter | Email Verifier (`EMAIL_VERIFIER`) | 0,5 |

Buscar personas en Apollo (3.1) y consultar el saldo de Hunter no cuestan.

## Cómo funciona

1. **Antes** de cada llamada que cuesta, se revisan dos topes: el de la **campaña** (una búsqueda ejecutada, `la_searchrun`) y el **presupuesto mensual** del tenant. Si el gasto no cabe, la llamada **no se hace**.
2. **Apenas** se gasta, se anota en el libro `la_creditledger`: proveedor, operación, créditos, costo estimado en USD, campaña, contacto, quién y cuándo. Si algo falla después, lo gastado no se pierde.
3. Cuando un tope se alcanza, la campaña se detiene con `lblCreditLimit`. Los contactos que faltaban se retoman en la siguiente ejecución, o el mes siguiente si el tope es mensual.

Cada tenant tiene su propia base, así que cada uno tiene su propio libro y sus propios topes. El mes es el mes calendario en hora de Colombia.

## Alertas

| Estado | Cuándo |
|---|---|
| `OK` | Por debajo del 80 % del presupuesto mensual |
| `WARNING` | 80 % o más del presupuesto, **o** el saldo real de Hunter (de toda la cuenta) no alcanza para una campaña |
| `EXCEEDED` | Se acabó el presupuesto del mes |

El estado se ve en `GET /msa-prospeccion/api/credits` (también en el panel, 6.1). El job de mantenimiento diario deja una alerta en el log por cada proveedor que no esté en `OK`. Cuando exista el buzón (5.2) se enviará además por correo.

**Ojo:** el plan de Apollo y el de Hunter son de toda la cuenta y se comparten entre tenants. El presupuesto mensual es por tenant; el saldo de Hunter avisa si la cuenta completa se está quedando sin créditos.

## Configuración (variables de entorno)

| Variable | Por defecto | Para qué |
|---|---|---|
| `APOLLO_MONTHLY_CREDITS` | 1000 | Presupuesto mensual de Apollo por tenant (0 = sin tope) |
| `APOLLO_CREDITS_PER_SEARCH` | 50 | Tope de Apollo por campaña (0 = sin tope) |
| `HUNTER_MONTHLY_CREDITS` | 500 | Presupuesto mensual de Hunter por tenant |
| `HUNTER_CREDITS_PER_SEARCH` | 50 | Tope de Hunter por campaña |
| `CREDITS_ALERT_PERCENT` | 80 | Porcentaje del presupuesto que dispara la alerta |
| `APOLLO_CREDIT_COST_USD`, `HUNTER_CREDIT_COST_USD` | vacío | Precio por crédito para el costo estimado |

**Los valores por defecto son provisionales:** hay que ajustarlos al plan que se compre (1.3) y repartirlos entre los tenants que usen el módulo.

## Pendiente

- Confirmar con Apollo si `people/match` cobra cuando no entrega el correo (hoy se anota solo cuando lo entrega).
- Anotar el costo de Gemini por tokens (se paga distinto: no tiene tope de créditos).
- Aviso por correo de las alertas (5.2).
- Mover los topes a `la_tenantconfig` si cada tenant necesita valores distintos.
