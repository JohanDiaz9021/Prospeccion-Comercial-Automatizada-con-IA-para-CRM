# Seguimiento de los correos enviados (tarea 5.4)

- **Estado:** reconocer y procesar lo que llega al buzón está listo y probado en local, también con mensajes MIME de
  verdad que viajan por Mailpit (5.2). Falta el conector del buzón real (Microsoft Graph o Gmail API) cuando TI defina
  el proveedor: solo debe entregar cada mensaje en MIME, que el sistema ya sabe leer (`MimeEmails.parse`).
- **Última actualización:** 2026-11-25

Cada 15 minutos el sistema lee el buzón de prospección y reconoce qué llegó, sin que nadie intervenga.

## Qué hace con cada mensaje

| Llega | Cómo lo reconoce | Qué hace |
|---|---|---|
| **Respuesta** | Contesta uno de nuestros correos | Evento "Respuesta": cuenta en el Panel como "Respondieron" y pasa al vendedor con una tarea en el CRM (5.6, [paso-al-vendedor.md](paso-al-vendedor.md)) |
| **Pedido de baja escrito** ("darme de baja", "no me escriban más", "remove me"...) | Frases de baja en lo que escribió la persona (no en nuestro correo citado) | Baja igual que el enlace (5.5): lista de bloqueo para siempre y se cancelan sus correos en cola |
| **"Fuera de la oficina"** | Encabezados de respuesta automática (Auto-Submitted, X-Autoreply) o asunto típico | **No cuenta** como respuesta: se ignora |
| **Rebote duro** (el buzón no existe) | Aviso de entrega fallida con código 5.x.x, o texto como "user unknown" | Evento "Rebote", lista de bloqueo para siempre y se cancelan sus correos en cola |
| **Rebote temporal** (buzón lleno, servidor caído) | Código 4.x.x o aviso de demora | Evento "Rebote". Al **tercero** a la misma dirección (`TRACKING_SOFT_BOUNCE_LIMIT`) se bloquea como rebote duro |
| **Queja** (lo marcaron como spam) | Reporte de queja del proveedor (formato ARF) | Lista de bloqueo para siempre, motivo "Queja" |

Reconocer es igual para Gmail y Microsoft 365: se usan los encabezados estándar del correo. El conector del
proveedor solo lee los mensajes.

## Cómo sabe de qué correo es

1. **Por nuestro Message-ID.** Cada correo sale con uno propio (`<pia.<envío>.<aleatorio>@<dominio>>`, se pone en la
   5.2). Las respuestas lo repiten en In-Reply-To y References, y los rebotes y quejas traen los encabezados del
   correo original.
2. Si no, **por el hilo** de la conversación en el proveedor.
3. Si no, **por la dirección**: el último correo enviado a esa persona (o, en un rebote, a la dirección que rebotó).

Un mensaje que no corresponde a ningún correo de prospección se deja quieto.

## Sin repetir y sin perder nada

- Cada lectura repasa lo que llegó en las **últimas 48 horas** (`MAILBOX_LOOKBACK_HOURS`). Cada mensaje se procesa
  **una sola vez** (por su ID en el proveedor), así que repasar no repite eventos ni bloqueos, y si el job se cae un
  rato, lo recupera en la siguiente lectura.
- Un mensaje que falla no detiene a los demás.
- Nunca se escriben correos ni texto de personas en los logs.

## Aperturas (píxel): apagado por defecto

El píxel es una imagen invisible que marca "abrió el correo". Es un dato **aproximado**: Apple y algunos antivirus
abren solos los correos y otros bloquean las imágenes. Por eso:

- Está construido pero **apagado** (`OPEN_PIXEL_ENABLED=false`). Aunque esté apagado, la ruta siempre devuelve la
  imagen: quien abre el correo nunca ve un error.
- Encenderlo requiere: visto bueno de Jurídica (1.10), `OPEN_PIXEL_BASE_URL` con la URL pública y abrir en el gateway
  la ruta `/msa-prospeccion/public/open` (hoy solo está abierta la de baja).
- Solo cuenta la primera apertura de cada correo. En el Panel sale como "Aperturas (aprox.)".
- Lleva una firma distinta a la del enlace de baja: uno no sirve por el otro.

## Probarlo en local

En el mini front, **Bandeja de Borradores** → abrir un borrador **aprobado** → "Probar el Seguimiento":

1. Enviarlo de verdad a Mailpit (**Cola de Envío → Enviar Ya**, ver [envio-de-correos.md](envio-de-correos.md)) o
   **Marcar como Enviado** sin enviarlo.
2. Simular lo que llega: Respuesta, Fuera de Oficina, Pide la Baja, Rebote Temporal, Rebote Duro o Queja. Con
   **Por Mailpit**, el mensaje viaja como correo MIME real a Mailpit y se lee de vuelta como el buzón real.

Los mensajes simulados traen los mismos encabezados que los de Gmail y Outlook y pasan por el mismo código que leerá
el buzón. El resultado se ve en el **Panel** y en **Cola de Envío → Lista de Bloqueo**.

Prueba hecha el 2026-11-24: respuesta registrada, "fuera de la oficina" ignorado, pedido de baja, rebote duro, queja
y tercer rebote temporal en la lista de bloqueo con su motivo; Panel con 6 enviados, 1 respuesta, 2 rebotes y 2
bajas; píxel apagado devolviendo la imagen sin registrar nada.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `MAILBOX_PROVIDER` | `none` | `smtp` (Mailpit, solo pruebas); `graph` o `gmail` cuando TI lo defina (1.11) |
| `MAILBOX_LOOKBACK_HOURS` | 48 | Horas que repasa cada lectura |
| `TRACKING_SOFT_BOUNCE_LIMIT` | 3 | Rebotes temporales antes de bloquear la dirección |
| `OPEN_PIXEL_ENABLED` | `false` | Píxel de apertura |
| `OPEN_PIXEL_BASE_URL` | vacío | URL pública del píxel por el gateway |
| `PROSPECCION_JOBS_ENABLED` | `false` | Enciende los jobs, entre ellos esta lectura cada 15 minutos |

## Pendiente

- Conector del buzón real (Graph o Gmail), cuando TI defina el proveedor: traer cada mensaje en MIME. Leer los
  encabezados y las partes del rebote o la queja ya lo hace `MimeEmails.parse` (5.2).
- Notificaciones del buzón en tiempo real: hoy se lee cada 15 minutos, que es suficiente; se pueden agregar después
  (necesitan una URL pública).
- Jurídica (1.10): decidir si se usa el píxel.
