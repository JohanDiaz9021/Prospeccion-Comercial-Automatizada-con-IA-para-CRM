# Mensaje base

- **Estado:** v1 aprobada por el equipo comercial (el pie legal sigue pendiente de jurídica, tarea 1.10)
- **Última actualización:** 2026-11-20
- **Aprueba:** gerencia comercial
- **Valor para cargar:** [mensaje-base.txt](mensaje-base.txt)

El mensaje base es el correo modelo de la empresa. Se guarda en `la_tenantconfig.BASE_MESSAGE` (uno por tenant). En **4.3** Gemini lo adapta a cada contacto calificado y una persona aprueba el borrador antes de enviarlo.

Gemini **adapta** el mensaje base, no lo reescribe: la oferta, la llamada a la acción y la firma salen siempre de aquí.

## Formato

Texto plano, máximo 5.000 caracteres (`TenantConfigDTO.baseMessage`).

| Elemento | Qué es | Quién lo llena |
|---|---|---|
| `ASUNTO: ...` | Primera línea: asunto sugerido | Gemini puede ajustarlo (máximo 60 caracteres) |
| `{{nombre}}`, `{{empresa}}`, `{{cargo}}`, `{{ciudad}}`, `{{sector}}` | Datos del contacto | Gemini, con los datos de Apollo. Si falta el dato, reescribe la frase sin él |
| `[[PERSONALIZAR: instrucción]]` | Parte que Gemini escribe siguiendo la instrucción | Gemini |
| Todo lo demás | Texto fijo: oferta, llamada a la acción, firma | Nadie: se conserva igual (solo concordancia y puntuación) |

`{{nombre}}` es solo el primer nombre (Colombia, trato de "usted").

## Lo que NO va en el mensaje base

Lo agrega el sistema con la plantilla [prospect-email.html](../../src/main/resources/templates/email/prospect-email.html), para que nunca dependa de Gemini ni se pueda borrar al editar el borrador:

- Enlace para darse de baja (token firmado).
- LinkedIn de la empresa (`la_tenantconfig.COMPANY_LINKEDIN_URL`).
- Pie legal: quién escribe, por qué recibe el correo y cómo darse de baja (ver la sección siguiente).

## Pie legal (propuesta v1 para jurídica)

- **Estado:** propuesta, pendiente de aprobación de jurídica (tarea 1.10).
- **Dónde va:** al final de cada correo, en la plantilla [prospect-email.html](../../src/main/resources/templates/email/prospect-email.html). Gemini no lo escribe y quien revisa el borrador no lo puede editar.
- **Datos:** el NIT, la dirección y el enlace a la política salen de `LEGAL_NIT`, `LEGAL_ADDRESS` y
  `LEGAL_PRIVACY_POLICY_URL` (y el nombre, de `LEGAL_COMPANY_NAME`). Sin ellos no se envía ningún correo (5.2).

Texto propuesto (`{{empresa}}` es la empresa del contacto; `{{enlace_baja}}` lo genera el sistema con un token firmado):

> Le escribimos a su correo corporativo porque su cargo en {{empresa}} se relaciona con los servicios de tecnología de GTC Corporation. Sus datos de contacto profesional provienen de una base de datos comercial B2B.
>
> Si no desea recibir más mensajes nuestros, puede darse de baja aquí: {{enlace_baja}}. Al darse de baja no volverá a recibir correos de GTC.
>
> GTC Corporation · NIT [completar] · [dirección y ciudad] · Política de tratamiento de datos: [enlace]

En qué se apoya (del documento "Marco legal y de políticas para la prospección automatizada"):

| Punto del pie | Por qué |
|---|---|
| Solo correo corporativo y motivo del contacto | Según la SIC, los datos corporativos (correo institucional) quedan fuera de la Ley 1581 porque son de la empresa; decir por qué se escribe deja claro que el contacto es B2B |
| Origen de los datos | El sistema guarda la evidencia de cada dato (`la_evidence`) y no usa scraping |
| Baja en un clic, gratuita y permanente | Ley 2300 y buenas prácticas: quien se da de baja queda en `la_suppression`. Además, cada correo lleva los encabezados `List-Unsubscribe` |
| Identificación de la empresa | Quien recibe sabe quién le escribe y cómo consultar la política de datos |

El horario de la Ley 2300 (lunes a viernes de 7:00 a 19:00 y sábados de 8:00 a 15:00, sin domingos ni festivos) no va en el pie: lo aplica el sistema al enviar.

Preguntas para jurídica:

1. ¿Se aprueba el texto tal como está?
2. ¿Hay que nombrar al proveedor de los datos (Apollo.io) o basta con "base de datos comercial B2B"?
3. ¿Son obligatorios el NIT, la dirección y el enlace a la política de tratamiento de datos, aunque la Ley 1581 no aplique a datos corporativos? Si van, ¿cuáles son los valores?
4. ¿La baja debe cubrir solo al correo o a toda la empresa del contacto?

## Reglas para el borrador (las aplica el prompt de 4.3)

1. **Español de Colombia, trato de usted**, tono profesional y cercano. Sin mayúsculas sostenidas, sin signos de exclamación seguidos, sin emojis.
2. **Largo:** el cuerpo final entre 90 y 160 palabras. Asunto de máximo 60 caracteres, sin "RE:" ni "FW:" falsos.
3. **Nada inventado:** solo datos que vienen de Apollo o del perfil. Prohibido: cifras, logros, clientes, casos de éxito, noticias de la empresa del contacto o supuestas conversaciones previas.
4. **Una sola llamada a la acción**, la del mensaje base.
5. **No prometer** precios, descuentos, plazos ni resultados.
6. Si falta el nombre de la persona: "Buenos días:". Si falta el cargo o el sector, la personalización se basa solo en la empresa.
7. La personalización usa el servicio del perfil que mejor encaja con el cargo y el sector (`company.services[].bestFor` del [perfil de interés](perfil-de-interes.md)).
8. Gemini devuelve asunto y cuerpo en HTML simple (párrafos, listas y negritas). El sistema limpia el HTML (`HtmlSanitizerUtil`) y saca la versión en texto plano.

## Cómo se aplica (4.3)

- **Qué ve Gemini:** el mensaje base, la parte `company` del perfil (propuesta de valor y servicios) y del contacto solo lo que tiene evidencia en `la_evidence`: primer nombre, cargo, empresa, sector, empleados y ciudad. No ve apellidos, correo ni IDs.
- **Prompt:** [draft-email.txt](../../src/main/resources/prompts/draft-email.txt) (versión `draft-v1`). Cada borrador guarda el modelo y la versión del prompt (`la_draft.MODEL` y `PROMPT_VERSION`).
- **Revisión en el código (`DraftRules`):**

| Tipo | Qué se revisa | Qué pasa |
|---|---|---|
| Error | Sin asunto o sin cuerpo; quedó una marca `{{...}}` o `[[...]]`; falta la firma (última línea del mensaje base); asunto de más de 150 caracteres | Se pide otra redacción. Si la segunda también falla, no se guarda nada (`lblDraftNotValid`) |
| Aviso | Asunto de más de 60 caracteres; cuerpo con menos de 90 o más de 160 palabras; un número que no está en el mensaje base ni en los datos (posible dato inventado); exclamaciones seguidas; emojis; palabras seguidas en mayúsculas | Se guarda, y quien revisa ve el aviso |

- **Versiones:** cada redacción es una fila nueva en `la_draft` (versión 1, 2, 3...), en estado "Por revisar". Si el contacto ya tiene una versión aprobada, no se redacta otra.
- **Ojo con el asunto:** el asunto aprobado ocupa 46 caracteres sin la empresa, así que con una empresa de más de 14 caracteres pasa de 60 (por ejemplo, "Clínica Los Andes" da 63). Gemini lo acorta por la regla 2 y, si no, sale el aviso. Conviene que el equipo comercial apruebe un asunto más corto.

## Ejemplo de borrador

Contacto ficticio: Laura, Directora de Tecnología de "Clínica Los Andes" (salud, 250 empleados, Bogotá).

> **Asunto:** Clínica Los Andes: menos procesos manuales, sistemas conectados
>
> Buenos días, Laura:
>
> En una clínica del tamaño de Clínica Los Andes, la dirección de tecnología suele tener que mantener al día varios sistemas (historia clínica, facturación, inventarios) que no siempre comparten la información.
>
> En GTC ayudamos a empresas medianas colombianas a conectar sus sistemas y automatizar procesos manuales para que operen más rápido y con menos errores. Por ejemplo:
>
> - Integramos el ERP, el CRM y la facturación para que la información esté en un solo lugar.
> - Automatizamos tareas repetitivas con software a la medida e inteligencia artificial.
> - Damos soporte y mantenimiento a plataformas críticas cuando el equipo interno no da abasto.
>
> ¿Le parece si conversamos 20 minutos la próxima semana para ver si algo de esto le sirve a Clínica Los Andes?
>
> Cordialmente,
>
> Equipo Comercial GTC

## Cómo se cambia

1. El equipo comercial propone el cambio.
2. Se actualizan este documento y [mensaje-base.txt](mensaje-base.txt).
3. Se carga en `la_tenantconfig` (`PUT /msa-prospeccion/api/config`, campo `baseMessage`). El servicio rechaza el texto si aún tiene marcas `[validar]`.
4. Los borradores ya generados no cambian; los nuevos usan el mensaje nuevo.

## Decisiones y pendientes

- [x] Texto de la oferta: igual a los servicios del perfil de interés.
- [x] Llamada a la acción: reunión de 20 minutos.
- [x] Firma: "Equipo Comercial GTC" (no una persona), que también es el nombre del remitente (`MAILBOX_FROM_NAME`).
  El buzón lo define TI (1.11).
- [ ] Pie legal y motivo del contacto, con jurídica: propuesta v1 en la sección "Pie legal".

## Registro de cambios

| Versión | Fecha | Cambio | Aprobó |
|---|---|---|---|
| v1 | 2026-11-11 | Borrador inicial | — |
| v1 | 2026-11-20 | Aprobado. Firma "Equipo Comercial GTC" | Equipo comercial |
