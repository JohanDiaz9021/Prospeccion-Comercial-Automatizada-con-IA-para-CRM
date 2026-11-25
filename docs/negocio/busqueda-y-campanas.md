# Búsqueda y campañas (tareas 3.2 y 3.4)

- **Estado:** v1, implementada y probada contra la base local con Apollo y Gemini simulados. Con Apollo real, en cuanto
  haya plan pago (People API Search y People Enrichment no están en el plan gratis).
- **Última actualización:** 2026-11-23

Una persona escribe a quién busca y el sistema hace el resto solo, hasta dejar el borrador del correo en la bandeja.
**El único paso humano es aprobar el correo (5.1).**

## Cómo funciona

1. **Se escribe la búsqueda** en lenguaje natural: "gerentes de TI de clínicas medianas en Bogotá".
2. **Gemini la convierte en filtros de Apollo** (cargos en español e inglés, niveles, ciudades, sectores, tamaños y
   palabras clave). El código limpia lo que propone: quita niveles y rangos que Apollo no conoce, repetidos y vacíos, y
   si no hay ubicación busca en Colombia. Si la frase no dice a quién buscar, lo avisa (`lblSearchNotUnderstood`).
3. **La persona revisa los filtros** y puede pedir una **vista previa** (total en Apollo y una muestra, 0 créditos).
4. **Al ejecutar**, la búsqueda sigue en segundo plano. La pantalla muestra su avance:

| Paso | Qué pasa | Costo |
|---|---|---|
| Apollo | Trae hasta **25 personas nuevas** (que nunca se habían procesado). Los competidores y las empresas excluidas a mano ni se buscan | 0 créditos |
| Calificación (3.5) | Gemini califica a cada una con el perfil de interés. Las que no alcanzan el umbral quedan **Descartadas por la IA** | Gemini |
| Correo (4.1) | Solo para las calificadas, con los topes de créditos de la 4.2 | Apollo y Hunter |
| Exclusiones | Con el correo: bajas, colaboradores, competidores, contacto o empresa ya prospectados | — |
| Contacto | Empresa, contacto y correo en el CRM, con la evidencia de cada dato | — |
| Borrador (4.3) | Gemini redacta el correo y queda **Por revisar** en la bandeja | Gemini |

5. **Campaña (3.4):** al ejecutar se elige si se repite **cada día hábil** o **cada semana**. El job corre a las
   **6:00** (lunes a viernes, hora de Colombia) y ejecuta las campañas a las que les toca:
   - **Diaria:** si no ha corrido hoy.
   - **Semanal:** si su última ejecución fue hace 7 días o más (el mismo día de la semana en que se creó).

   Cada ejecución usa los mismos filtros y el perfil vigente, y solo procesa personas nuevas. Así los borradores están
   en la bandeja antes de que abra el horario de envío (7:00). Se pausa, se reactiva y se cambia la frecuencia desde el
   historial. La hora del job se cambia con `SCHEDULED_SEARCH_CRON`.

## Reglas

- **Nunca se paga dos veces por la misma persona:** cada persona procesada queda en `la_apolloseen` con su resultado
  (Contacto con borrador, Descartada por la IA, Sin correo o Excluida).
- **Una sola persona por empresa:** si la empresa ya es prospecto, no se le escribe a otra persona de la misma empresa.
- **Techo de gasto por ejecución:** 25 personas nuevas (`SEARCH_MAX_NEW_PER_RUN`), además de los topes de créditos
  por campaña y por mes (4.2). Si se acaba un tope o el cupo de Apollo, la ejecución termina con lo que alcanzó y
  las personas que faltaron se toman en la siguiente.
- **Si Gemini no logra calificar a alguien**, no queda como vista: se intenta otra vez en la siguiente ejecución.
- **De quien no se contacta no se guardan datos personales:** solo su ID de Apollo, el resultado y el puntaje.
- **Si el borrador falla** (por ejemplo, falta el mensaje base), el contacto queda creado y el borrador se puede pedir
  de nuevo desde la bandeja. La ejecución lo anota en sus observaciones.

## Exclusiones

| Motivo | De dónde sale |
|---|---|
| Se dio de baja, rebotó o se quejó | Lista de bloqueo (`la_suppression`, 5.5) |
| Es colaborador | `exclusions.ownDomains` del perfil (dominios de la propia empresa) |
| Competidor o empresa excluida a mano | `exclusions.competitorDomains` y `exclusions.excludedCompanyDomains` del perfil. No se buscan en Apollo |
| La prospección ya tiene el contacto o la empresa | `la_evidence` (correo) y `la_leafletext` (dominio) |
| El correo ya está en el CRM (3.4) | Contactos de prospectos, también los creados a mano (`pm_leafletcontactmedium`), y proveedores: clientes, aliados y colaboradores (`pv_contactmediumprovider`) |
| Alguien de la empresa ya está en el CRM (3.4) | Algún correo de esas mismas tablas con el mismo dominio (el del correo o el de la empresa según Apollo) |

Las reglas van de la más barata a la más cara: las consultas al CRM solo se hacen si las anteriores no excluyeron a la
persona. Las tablas del CRM se leen directo (solo lectura): viven en la misma base del tenant y msa-cotizaciones no
tiene un endpoint para buscar por correo. Los correos personales (gmail, hotmail...) nunca llegan hasta aquí: se
descartan al conseguir el correo (4.1).

## Dónde queda el contacto

- **QA y producción:** en el CRM por msa-cotizaciones (`register-complete-prospect-by-user-id`): `pm_leaflet`,
  `pm_leafletcontact` y el correo en `pm_leafletcontactmedium`, con la lista de contactos si se eligió una.
- **Local:** directo en las tablas `pm_*` de la base **local** de Docker (`LocalCrmProspectAdapter`), porque
  msa-cotizaciones en desarrollo apunta a la base compartida. El adaptador se niega a escribir si la base no está en
  localhost.
- Siempre: `la_leafletext` (empresa), `la_contactext` (cargo, puntaje, origen del correo, búsqueda) y `la_evidence`
  (cada dato con su fuente).

## Qué se guarda de cada búsqueda

`la_searchrun`: la frase, los filtros (JSON), la configuración usada, el embudo (encontradas en Apollo, nuevas,
calificadas), el estado (Pendiente, En curso, Terminada, Fallida) y las observaciones. Dos columnas nuevas, fuera del
diagrama v3.5: `FLG_SCHEDULED` (es campaña activa), `SCHEDULE_FREQUENCY` (`DAILY` o `WEEKLY`) y
`PARENT_SEARCHRUNID` (campaña que originó una ejecución automática).

## Endpoints

| Para qué | Ruta |
|---|---|
| Convertir la frase en filtros | `POST /msa-prospeccion/api/search/interpret` |
| Vista previa (0 créditos) | `POST /msa-prospeccion/api/search/preview` |
| Ejecutar (y crear la campaña con `scheduled: true`) | `POST /msa-prospeccion/api/search/run` |
| Historial con el embudo | `GET /msa-prospeccion/api/search/runs` |
| Avance de una búsqueda | `GET /msa-prospeccion/api/search/runs/{id}` |
| Qué pasó con cada persona | `GET /msa-prospeccion/api/search/runs/{id}/results` |
| Activar, pausar o cambiar la frecuencia (`{"scheduled": true, "frequency": "WEEKLY"}`) | `PATCH /msa-prospeccion/api/search/runs/{id}/schedule` |
| Solo local: correr las campañas ya (`force=false`: solo las que tocan hoy, como el job) | `POST /msa-prospeccion/api/dev/search/run-scheduled` |
| Solo local: borrar las búsquedas y lo que crearon | `DELETE /msa-prospeccion/api/dev/search/data` |

## Probarlo en local

Sin `APOLLO_API_KEY` en el `.env`, `tools/crm-local` arranca el servicio contra el **Apollo simulado**
(`tools/mini-front/apollo_simulado.py`, puerto 8772): personas y empresas inventadas, con dominios `.example`, que
cumplen los filtros. Todos los correos vienen verificados y quien no tiene correo tampoco tiene dominio, así que el
servicio nunca llama a Hunter con datos inventados. Cuando haya plan pago, se pone la llave en el `.env`, se reinicia
el servicio y la misma búsqueda usa datos reales, sin cambiar código.

En el mini front: pestaña **Búsqueda y Campañas**.

## Pendiente

- Plan pago de Apollo y revisar los filtros con respuestas reales (por ejemplo, si `organization_industries[]`
  filtra como se espera).
- Usuario "robot" para los jobs: hoy las ejecuciones automáticas quedan a nombre de quien creó la campaña.
- Permisos por rol (2.9, espera la 1.13).
- La pantalla real dentro del CRM (3.3).
- Si una instancia se reinicia en medio de una búsqueda, esa ejecución queda "En curso". Revisarlo con el job de
  mantenimiento.
