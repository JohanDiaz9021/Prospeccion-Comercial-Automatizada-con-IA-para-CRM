# Prerrequisitos de TI para Prospección IA

- **Tarea:** 1.11 (también prepara la 1.12, calentamiento del buzón, y la 2.6, llaves en AWS SSM)
- **Responsable:** TI
- **Fecha límite original:** 30/10/2026 (vencida). El calentamiento del buzón (1.12) dura 14 días hábiles y no puede empezar sin esto, así que cada día de atraso corre la fecha del piloto.

## 0. Decisión que va primero: Google Workspace o Microsoft 365

El buzón de prospección puede ser de cualquiera de los dos; el sistema ya admite ambos (`MAILBOX_PROVIDER=gmail` o `graph`). La presentación del proyecto propone **Google Workspace**. TI confirma cuál y, a partir de ahí, aplica solo la columna que corresponde en las secciones 3 y 4.

## 1. Dominio secundario

- Un dominio **distinto** del principal de GTC, para que si cae en spam no afecte los correos de cotizaciones ni los del día a día. Por ejemplo, una variante del nombre de la empresa (el nombre lo eligen TI y comercial).
- El sitio web del dominio redirige al sitio principal de GTC, para que quien lo revise vea una empresa real.

## 2. Buzones

| Buzón | Para qué |
|---|---|
| Principal (por ejemplo `prospeccion@<dominio secundario>`) | Envía los correos aprobados y recibe las respuestas. Con licencia propia, **separado del buzón de cotizaciones** |
| Respaldo (segundo buzón en el mismo dominio) | Se calienta en paralelo (tarea 1.12) para seguir enviando si el principal tiene problemas de reputación |

Ambos deben estar a nombre de una persona real de comercial (nombre y firma), no como "noreply".

## 3. Registros DNS del dominio secundario

| Registro | Google Workspace | Microsoft 365 |
|---|---|---|
| MX | `smtp.google.com`, prioridad 1 (según la consola de administración) | El valor `<dominio>.mail.protection.outlook.com` que muestra el centro de administración |
| SPF (TXT en la raíz) | `v=spf1 include:_spf.google.com ~all` | `v=spf1 include:spf.protection.outlook.com -all` |
| DKIM | Consola de administración → Aplicaciones → Google Workspace → Gmail → Autenticar correo electrónico. Generar llave de 2048 bits, publicar el TXT `google._domainkey` y pulsar "Iniciar autenticación" | Dos CNAME, `selector1._domainkey` y `selector2._domainkey`, con los valores que muestra Microsoft Defender (Correo electrónico y colaboración → Directivas → DKIM). Luego, activar la firma |
| DMARC (TXT en `_dmarc`) | `v=DMARC1; p=none; rua=mailto:<buzón que reciba los reportes>` | Igual |

Sobre DMARC: se empieza en `p=none` para recibir reportes sin bloquear nada. Al terminar el calentamiento, si los reportes están limpios, se pasa a `p=quarantine`.

**Cómo comprobarlo:** al enviar un correo de prueba a una cuenta de Gmail, en "Mostrar original" deben aparecer `SPF: PASS`, `DKIM: PASS` y `DMARC: PASS`.

## 4. Permiso para que el sistema envíe y lea el buzón por API

El sistema envía uno a uno, lee respuestas y rebotes, y no usa SMTP.

| | Google Workspace (Gmail API) | Microsoft 365 (Microsoft Graph) |
|---|---|---|
| Qué se crea | Proyecto en Google Cloud con la **Gmail API** habilitada y una **cuenta de servicio** | **Registro de aplicación** en Microsoft Entra ID |
| Permisos | Delegación de todo el dominio (Consola de administración → Seguridad → Control de acceso y de datos → Controles de API) con los alcances `https://www.googleapis.com/auth/gmail.send` y `https://www.googleapis.com/auth/gmail.readonly` | Permisos de **aplicación** `Mail.Send` y `Mail.Read`, con consentimiento del administrador |
| Restricción | Solo se usa para suplantar al buzón de prospección | Limitar la aplicación al buzón de prospección (RBAC para aplicaciones en Exchange Online, o la directiva de acceso de aplicación) |
| Credencial que se entrega | Archivo JSON de la cuenta de servicio | ID del inquilino, ID de la aplicación y certificado o secreto |

Límites de envío del proveedor (verificar los vigentes): Google Workspace permite unos 2.000 mensajes diarios por usuario y Exchange Online, unos 10.000 destinatarios diarios. El sistema usa un límite propio mucho menor (`DAILY_SEND_LIMIT`) durante el calentamiento.

## 5. Facturación de Gemini

- La llave de la API de Gemini debe pertenecer a un proyecto de Google Cloud **con facturación activa**.
- En el uso gratuito, Google puede usar las solicitudes para mejorar sus productos y pide no enviar datos personales; el sistema sí envía datos de los contactos. Detalle en el documento "Marco legal y de políticas para la prospección automatizada".
- Antes del piloto se pone un tope de gasto o una alerta de presupuesto en ese proyecto.

## 6. Dónde se entregan las credenciales (tarea 2.6)

Todas van en **AWS SSM Parameter Store como `SecureString`**. Nunca por chat, correo ni en el código. Nombres propuestos (TI los confirma; siguen la ruta que ya usa el CRM, `/config/gtcproject/...`):

| Parámetro | Contenido |
|---|---|
| `/config/gtcproject/prospeccion/apollo-api-key` | Llave de Apollo (plan de pago) |
| `/config/gtcproject/prospeccion/hunter-api-key` | Llave de Hunter |
| `/config/gtcproject/prospeccion/gemini-api-key` | Llave de Gemini con facturación |
| `/config/gtcproject/prospeccion/unsubscribe-secret` | Secreto aleatorio de 32 bytes o más para firmar los enlaces de baja |
| `/config/gtcproject/prospeccion/mailbox/<buzón>` | Credencial del buzón (JSON de la cuenta de servicio o datos de la aplicación de Entra) |

El rol con el que corre `msa-prospeccion-crm` en AWS necesita `ssm:GetParameter` sobre `/config/gtcproject/prospeccion/*` y el permiso de descifrado (`kms:Decrypt`) de esa llave.

## Lista de entrega

| # | Entregable | Responsable | Listo |
|---|---|---|---|
| 1 | Decisión: Google Workspace o Microsoft 365 | TI | ☐ |
| 2 | Dominio secundario registrado | TI | ☐ |
| 3 | Buzón principal y de respaldo, con licencia | TI | ☐ |
| 4 | MX, SPF, DKIM y DMARC publicados y comprobados (PASS) | TI | ☐ |
| 5 | Permisos de API sobre el buzón (sección 4) | TI | ☐ |
| 6 | Proyecto de Gemini con facturación y alerta de gasto | TI | ☐ |
| 7 | Credenciales en AWS SSM y permiso del rol del servicio | TI | ☐ |
| 8 | Aviso a Desarrollo para empezar el calentamiento (1.12) | TI | ☐ |
