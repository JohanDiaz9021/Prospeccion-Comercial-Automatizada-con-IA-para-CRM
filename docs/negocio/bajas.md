# Bajas con enlace firmado (tarea 5.5)

- **Estado:** v1, implementada y probada contra la base local. El enlace y los encabezados ya van en cada correo (5.2).
- **Última actualización:** 2026-11-23

Quien recibe un correo de prospección puede dejar de recibirlos **en un clic, gratis y para siempre**, sin que nadie
de GTC intervenga.

## Cómo funciona

1. Cada correo lleva un **enlace de baja** en el pie y el encabezado `List-Unsubscribe` (con `List-Unsubscribe-Post:
   List-Unsubscribe=One-Click`), que Gmail y Outlook muestran como "Cancelar suscripción".
2. **El enlace abre una página** con el correo enmascarado (`l****@empresa.co`) y un solo botón: **"Darme de Baja"**.
   Abrir el enlace no da de baja por sí solo, porque los antivirus de correo abren los enlaces automáticamente.
3. **Al confirmar** (o con la baja en un clic de Gmail u Outlook):
   - El correo entra a la **lista de bloqueo** (`la_suppression`, motivo "Baja voluntaria") para siempre.
   - Se registra **un** evento de baja en `la_emailevent`.
   - Se **cancelan** los demás correos en cola a esa dirección.
4. Desde ese momento **no se le vuelve a redactar ni a enviar**: la redacción (4.3) y la aprobación (5.1) lo rechazan
   (`lblContactSuppressed`).

Repetir la baja no hace nada nuevo. Un enlace alterado o incompleto muestra "Este enlace no es válido".

## Seguridad

- El enlace lleva un **token firmado** (HMAC-SHA256) con el tenant y el envío. No vence: la baja debe funcionar siempre.
- El secreto de firma sale de **AWS SSM** en QA y producción (`UNSUBSCRIBE_TOKEN_SECRET_SSM_PARAMETER`, mínimo 32
  caracteres). Cambiarlo invalida todos los enlaces ya enviados: no se rota sin un plan.
- La página es pública (sin JWT), no muestra el correo completo, no usa recursos externos y envía
  `Referrer-Policy: no-referrer` para que el token no se filtre.
- El correo nunca se escribe en los logs.

## Qué se guarda

| Tabla | Qué |
|---|---|
| `la_sendqueue.RECIPIENT_EMAIL` | Destinatario de cada envío (columna nueva, fuera del diagrama v3.5). Sale de la evidencia del contacto |
| `la_evidence` (campo `email`) | El correo aceptado en la 4.1, con su fuente (APOLLO o HUNTER) |
| `la_suppression` | Correo, dominio, motivo, envío que lo originó, quién y cuándo |
| `la_emailevent` | Evento "Baja" del envío |

## Endpoints

| Para qué | Ruta |
|---|---|
| Página de baja (pública) | `GET /msa-prospeccion/public/unsubscribe?t=...` |
| Confirmar baja / un clic (pública) | `POST /msa-prospeccion/public/unsubscribe?t=...` |
| Lista de bloqueo | `GET /msa-prospeccion/api/suppressions` |

En QA y producción, `UNSUBSCRIBE_BASE_URL` debe ser la URL pública por el gateway. En el mini front, la pestaña
**Bandeja de Borradores** tiene "Probar Enlace de Baja" en los aprobados y **Cola de Envío** muestra la lista de bloqueo.

## Pendiente

- Jurídica (1.10): ¿la baja cubre solo el correo o toda la empresa del contacto? Hoy, solo el correo (el dominio se
  guarda por si se decide ampliar).
- Secreto de firma en SSM de QA y producción (TI).
