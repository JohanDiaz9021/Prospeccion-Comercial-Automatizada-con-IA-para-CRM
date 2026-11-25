# Correo de contacto (tarea 4.1)

- **Estado:** v1, implementado en `EmailDiscoveryService` con respuestas simuladas. Falta la prueba con llaves reales.
- **Última actualización:** 2026-11-20

Para cada contacto que **pasa el umbral** de calificación (3.5), el sistema consigue su correo de trabajo. Es el primer paso que **gasta créditos**, por eso va después de calificar.

## Cómo se consigue

| Paso | Qué se hace | Costo |
|---|---|---|
| 1 | Apollo People Enrichment (`POST /people/match`) con el ID de la búsqueda. Trae el correo, el apellido completo y la empresa con su dominio. Nunca se piden correos personales ni teléfonos | 1 crédito de Apollo si entrega el correo |
| 2a | Si Apollo lo marca `verified` se acepta tal cual | — |
| 2b | Si no, se verifica con Hunter Email Verifier | 0,5 crédito de Hunter |
| 3 | Si Apollo no tiene correo: Hunter Email Finder con nombre, apellido y dominio | 1 búsqueda de Hunter, **solo si lo encuentra** |
| 3a | Si el finder lo trae `valid` o `accept_all` con puntaje de 80 o más, se acepta | — |
| 3b | Si no, se verifica con Hunter Email Verifier | 0,5 crédito de Hunter |

## Cuándo se acepta o se descarta

| Estado de Hunter | Qué significa | Decisión |
|---|---|---|
| `valid` | El correo existe | Se acepta |
| `accept_all` | El servidor acepta cualquier dirección: no se puede confirmar | Se acepta (configurable) |
| `invalid` | No existe | Se descarta |
| `webmail` | Gmail, Outlook y similares | Se descarta |
| `disposable` | Correo temporal | Se descarta |
| `unknown` | No se pudo verificar | Se descarta |

Además se descartan los correos de los dominios personales del perfil (`exclusions.freeEmailDomains`: gmail.com, hotmail.com...).

**Por qué tan estricto:** el buzón de envío es nuevo (calentamiento, 1.12). Los rebotes dañan su reputación y hacen que los correos caigan en spam, así que es mejor perder un contacto dudoso que rebotar.

## Resultados posibles

| Resultado | Qué pasa después |
|---|---|
| `FOUND` | Se guarda el correo (`la_contactext.EMAIL_SOURCE` = APOLLO o HUNTER, `EMAIL_VERIFICATION` = estado) y sigue la redacción (4.3) |
| `NO_MATCH` | Apollo no reconoce la persona |
| `NOT_FOUND` | Nadie tiene el correo, o falta el dominio o el apellido para buscarlo |
| `DISCARDED` | Hay correo, pero no sirve (ver la tabla anterior) |
| `OPTED_OUT` | Hunter respondió 451: la persona pidió no ser procesada. Va a la lista de supresión (5.5) |

Si se acaba el cupo de Hunter (429), falta una llave o hay fallas de red, el proceso se detiene y el job lo reintenta más tarde. No se pierde el contacto.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `EMAIL_TRUST_APOLLO_VERIFIED` | `true` | Aceptar el `verified` de Apollo sin gastar una verificación de Hunter |
| `EMAIL_ACCEPTED_HUNTER_STATUSES` | `valid,accept_all` | Estados de Hunter que se aceptan. Para ser más estrictos: `valid` |
| `EMAIL_MIN_FINDER_SCORE` | `80` | Puntaje mínimo del finder para no verificar aparte |

## Pendiente

- Confirmar con una respuesta real de Apollo los campos de `people/match` (la documentación no estuvo disponible al implementar).
- Registrar los créditos gastados en `la_creditledger` y respetar el tope por tenant (4.2). El resultado ya trae los créditos de cada llamada.
- Guardar el resultado en `la_contactext` y la supresión de `OPTED_OUT`, dentro del proceso de búsqueda (3.3 y 5.5).
