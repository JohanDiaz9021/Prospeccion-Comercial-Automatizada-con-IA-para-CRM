# Cola de envío y horario de la Ley 2300 (tarea 5.3)

- **Estado:** v1, implementada y probada contra la base local. El envío real (5.2) ya toma los correos de esta cola:
  ver [envio-de-correos.md](envio-de-correos.md).
- **Última actualización:** 2026-11-25

Cuando una persona aprueba un borrador (5.1), el correo no sale de inmediato: entra a la cola con su **turno**, la hora
exacta en que se enviará. El turno siempre cumple tres reglas.

## Reglas del turno

| Regla | Qué hace | Dónde se configura |
|---|---|---|
| **Horario de la Ley 2300** | Lunes a viernes de 7:00 a 19:00 y sábados de 8:00 a 15:00, hora de Colombia. Nunca domingos ni festivos. A las 19:00 (o 15:00 el sábado) en punto ya no se envía | `prospeccion.ley2300` en `application.yml` |
| **Espaciado** | Entre dos correos pasan al menos `MIN_SPACING_SECONDS` segundos | Configuración del tenant (pestaña Configuración) |
| **Límite diario** | Máximo `DAILY_SEND_LIMIT` correos por día. Si el día se llena, el turno pasa al siguiente día hábil | Configuración del tenant |

Si la configuración aún no define el límite o el espaciado, se usan los valores de arranque: **20 correos por día y 120
segundos**, pensados para el calentamiento del buzón (1.12). Con `DAILY_SEND_LIMIT = 0` el envío queda **pausado**:
los correos se encolan, pero no sale ninguno.

Ejemplo: con 20 por día y 2 minutos de espaciado, si se aprueban 6 correos el sábado a las 14:50, salen a las 14:50,
14:52, 14:54, 14:56 y 14:58, y el sexto el lunes a las 7:00.

## Festivos

Se calculan solos para cualquier año (Ley 51 de 1983):

- **Fijos:** 1 de enero, 1 de mayo, 20 de julio, 7 de agosto, 8 y 25 de diciembre.
- **Se corren al lunes:** Reyes, San José, San Pedro y San Pablo, Asunción, Día de la Raza, Todos los Santos e
  Independencia de Cartagena.
- **Según la Pascua:** Jueves y Viernes Santo, Ascensión, Corpus Christi y Sagrado Corazón.

Un día no hábil extraordinario que decrete el Gobierno se agrega en `prospeccion.ley2300.extra-holidays`.

## Cómo funciona

1. **Al aprobar (5.1):** `SendQueueService.enqueue` crea la fila en `la_sendqueue` en estado "En cola", con el turno en
   UTC. Si el borrador ya estaba en la cola, no crea otra. Se hace en la misma transacción de la aprobación.
2. **Cada 5 minutos (job `send-dispatcher`):** solo dentro del horario de la Ley 2300 y con cupo diario, toma los correos
   cuyo turno ya llegó. Hoy no envía nada porque no hay buzón (`MAILBOX_PROVIDER=none`): lo deja en el log.
3. **Al enviar (5.4):** tomará las filas con `FOR UPDATE SKIP LOCKED`, revisará la lista de bloqueo (5.5), armará el
   correo con la plantilla y el enlace de baja y lo marcará enviado o fallido.

Cuentan para el límite y el espaciado los correos en cola y los enviados; los fallidos y cancelados no.

## Endpoints

| Para qué | Ruta |
|---|---|
| Estado de la ventana y de la cola | `GET /msa-prospeccion/api/sending/status` |
| Simular turnos sin guardar nada | `GET /msa-prospeccion/api/sending/preview?count=10&from=2026-11-28T14:50:00` |
| Festivos del año | `GET /msa-prospeccion/api/sending/holidays?year=2026` |

En el mini front se ven en la pestaña **Cola de Envío**.

## Pendiente

- Conector del buzón real (Graph o Gmail) cuando TI lo defina (1.11). El envío, los reintentos, la revisión de la lista
  de bloqueo y el enlace de baja ya están (5.2, [envio-de-correos.md](envio-de-correos.md)).
- Confirmar con jurídica si la ventana de los sábados (8:00 a 15:00) y la exclusión de festivos coinciden con su
  lectura de la Ley 2300 (1.10).
