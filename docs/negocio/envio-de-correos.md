# Envío de los correos (tarea 5.2)

- **Estado:** parte común lista y probada en local con Mailpit: armar el correo, enviarlo con turno, reintentos y
  marca de enviado o fallido, y leer lo que llega al buzón en MIME. Falta el conector del buzón real (Microsoft 365 o
  Gmail), que se agrega cuando TI defina el proveedor (1.11): solo entrega y lee el MIME que ya arma el sistema.
- **Última actualización:** 2026-11-25

El único paso humano es aprobar el correo (5.1). Desde ahí todo es automático: el correo espera su turno en la cola
(5.3), sale por el buzón de prospección y su seguimiento (5.4) reconoce respuestas, rebotes y quejas.

## Qué lleva cada correo

| Parte | De dónde sale |
|---|---|
| Asunto y cuerpo | El borrador aprobado, limpio otra vez antes de enviar |
| Remitente | `MAILBOX_FROM_ADDRESS` con el nombre `MAILBOX_FROM_NAME` ("Equipo Comercial GTC") |
| LinkedIn de la empresa | `la_tenantconfig.COMPANY_LINKEDIN_URL` (pestaña Configuración). Si falta o no es https, ese renglón no sale |
| Pie legal | Plantilla [prospect-email.html](../../src/main/resources/templates/email/prospect-email.html) con `LEGAL_COMPANY_NAME`, `LEGAL_NIT`, `LEGAL_ADDRESS` y `LEGAL_PRIVACY_POLICY_URL` |
| Enlace de baja | Firmado por envío (5.5), en el pie y en los encabezados `List-Unsubscribe` y `List-Unsubscribe-Post` (baja en un clic: Gmail y Outlook muestran "Cancelar suscripción") |
| Message-ID | `<pia.<envío>.<aleatorio>@<dominio del buzón>>`: las respuestas y los rebotes lo repiten, así se sabe a qué envío pertenecen (5.4) |
| Versiones | Texto y HTML (`multipart/alternative`) |

Nada de esto lo escribe Gemini ni se puede borrar al editar el borrador.

**No se envía nada** mientras falte alguno de estos datos: remitente, NIT, dirección, enlace a la política de datos
o la página de baja. Un pie con "[completar]" tampoco sale. El log dice qué falta.

## Cómo sale

1. Con los jobs encendidos, el despachador revisa la cola **cada minuto** dentro del horario de la Ley 2300.
2. Toma el próximo correo con turno con `FOR UPDATE SKIP LOCKED`: si hay dos instancias del servicio, nunca envían el
   mismo.
3. Vuelve a revisar justo antes de enviar:
   - Si el contacto se dio de baja o rebotó mientras esperaba, el correo queda **Cancelado**.
   - Si el borrador ya no está aprobado, también queda **Cancelado**.
4. Arma el correo, lo entrega al buzón y lo deja **Enviado** con su evento (el Panel lo cuenta).
5. Respeta el **espaciado** entre correos, el **límite diario** y un tope de 10 correos por pasada.

| Si falla | Qué pasa |
|---|---|
| El buzón no responde (servidor caído, tiempo agotado) | Se reintenta a los 15 y a los 30 minutos. Al tercer intento queda **Fallido**. La pasada se detiene: no insiste con los demás |
| El servidor rechaza la dirección | **Fallido** de una vez: reintentar no sirve. Los demás siguen |
| El correo no se puede armar (por ejemplo, quedó una marca `{{nombre}}`) | **Fallido** de una vez, sin llegar al buzón |

Si el servicio se cayera justo después de entregar un correo y antes de guardarlo, ese correo podría salir dos veces.
Es el costo de no perder ninguno; en la operación normal no pasa.

## Proveedores

| `MAILBOX_PROVIDER` | Para qué |
|---|---|
| `none` | Sin buzón: los correos esperan en la cola |
| `smtp` | **Solo pruebas, con Mailpit.** Por seguridad solo acepta un servidor local; otro exige `SMTP_ALLOW_REMOTE_HOST=true` a propósito |
| `graph` / `gmail` | Buzón real cuando TI lo defina (1.11). Usan las mismas piezas: el MIME que arma el sistema y el lector de MIME para lo que llega |

## Probarlo en local con Mailpit

Mailpit es un buzón de prueba: atrapa todo lo que se le envía y **nunca lo entrega a nadie**.

1. Levantar Mailpit:

   ```powershell
   docker compose -f docker-compose.local.yml --profile correo up -d mailpit
   ```

2. En el `.env`: `MAILBOX_PROVIDER=smtp`. En local, el resto ya tiene valores de prueba: servidor `localhost:1025`,
   remitente `prospeccion@gtc-prueba.local` y un pie legal de prueba.
3. Reiniciar `msa-prospeccion`.
4. Aprobar uno o varios borradores. En el mini front, **Cola de Envío → Probar el Envío → Enviar Ya**: envía sin
   esperar el turno ni el horario, pero respeta el límite diario.
5. Ver los correos en **http://localhost:8025**: asunto, cuerpo, pie, encabezados y "Cancelar suscripción".
6. Seguimiento: en **Bandeja de Borradores**, abrir el aprobado, marcar **Por Mailpit** y simular una respuesta, un
   rebote o una queja. El mensaje viaja como correo real a Mailpit y el sistema lo lee de vuelta por el mismo camino
   que el buzón de verdad. **Leer el Buzón** vuelve a leerlo; lo ya procesado no se repite.

Prueba hecha el 2026-11-24, con contactos `[DEMO]` inventados y un Mailpit de prueba:
- 3 correos enviados con su Message-ID, `List-Unsubscribe` de un clic, pie legal, texto y HTML.
- Respuesta, rebote duro y queja leídos de vuelta por Mailpit y bien clasificados. Leer otra vez no repitió nada.
- El rebote y la queja dejaron sus direcciones en la lista de bloqueo, y la baja en un clic funcionó.
- Los logs no tienen ningún correo de persona.
- Con el buzón apagado apareció un error: "Enviar Ya" gastaba los tres intentos de una vez. Se corrigió (la pasada se
  detiene al primer error temporal) y quedó cubierto por pruebas automáticas. Falta repetir esa prueba en vivo.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `MAILBOX_PROVIDER` | `none` | `smtp` (Mailpit), `graph` o `gmail` |
| `MAILBOX_FROM_ADDRESS` | vacío (local: `prospeccion@gtc-prueba.local`) | Buzón que envía; su dominio va en el Message-ID |
| `MAILBOX_FROM_NAME` | Equipo Comercial GTC | Nombre que ve el destinatario |
| `MAILBOX_MAX_ATTEMPTS` | 3 | Intentos antes de dejarlo fallido |
| `MAILBOX_RETRY_MINUTES` | 15 | Espera entre intentos (15, 30...) |
| `MAILBOX_MAX_PER_RUN` | 10 | Tope de correos por pasada del despachador |
| `SMTP_HOST`, `SMTP_PORT` | vacío (local: `localhost`, 1025) | Servidor SMTP (Mailpit) |
| `MAILPIT_API_URL` | vacío (local: `http://localhost:8025`) | Leer el buzón desde Mailpit |
| `LEGAL_COMPANY_NAME`, `LEGAL_NIT`, `LEGAL_ADDRESS`, `LEGAL_PRIVACY_POLICY_URL` | vacíos (local: de prueba) | Pie legal; sin ellos no se envía nada |

Los límites (correos por día y segundos entre correos) están en la pestaña **Configuración** (6.2).

## Pendiente

- Conector del buzón real (Graph o Gmail) cuando TI defina el proveedor (1.11). Solo debe entregar el MIME
  (`MimeEmails.build`) y leer los mensajes en MIME (`MimeEmails.parse`); el resto ya está.
- Datos reales del pie legal: NIT, dirección y política de datos (jurídica, 1.10).
- Calentamiento del buzón (1.12): subir poco a poco los correos por día en la pestaña Configuración.
- Aviso por correo de las alertas de créditos (4.2), ahora que hay envío.
