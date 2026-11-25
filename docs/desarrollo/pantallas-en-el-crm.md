# Pantallas de Prospección IA dentro del CRM (tarea 3.3)

- **Estado:** lista en local: módulo expuesto, ruta en el shell y las cuatro pestañas **Panel (6.1)**, **Búsqueda y
  Campañas (3.2)**, **Bandeja de Borradores (5.1)** y **Configuración (6.2)**.
- **Repos:** `mf-cotizaciones` y `gtc-crm-root`, rama `feature/prospeccion-ia`. Se entregan por ZIP.
- **Última actualización:** 2026-11-24

## Cómo se conecta

```
gtc-crm-root (shell, 4200)  ──/home/prospeccion-ia──▶  mf-cotizaciones (4201) './ProspeccionIa'
        │ dynamicMenuGuard (permiso "Abrir" del ítem de menú)            │
        │                                                              ▼
        └──────────── gateway (8090) /msa-prospeccion/api/** ──▶ msa-prospeccion
```

| Repo | Cambio |
|---|---|
| `mf-cotizaciones` | `src/app/gui/prospeccion-ia/` (componentes, servicio y estilos), `'./ProspeccionIa'` en `webpack.config.js`, ruta `prospeccion-ia` en `app.routes.ts` y `APIProspeccion` en los tres `environment*.ts` |
| `gtc-crm-root` | Ruta `prospeccion-ia` en `app.routes.ts` (con `dynamicMenuGuard`) y, **solo para el modo mock local**, el ítem "Prospección IA" (assignmentId 128) en `menu.mock.ts` y `permission.mock.ts` |

## Estructura en `mf-cotizaciones`

| Archivo | Qué hace |
|---|---|
| `prospeccion-ia.component.*` | Contenedor con pestañas y **todos los estilos v5**, anidados en `.pia` (`ViewEncapsulation.None`, para que las pantallas hijas los compartan sin tocar el resto del CRM) |
| `prospeccion-ia-api.service.ts` | Cliente de msa-prospeccion con el token de la sesión del CRM. Nunca lanza: cada llamada termina en `ApiResult` (ok o el motivo) |
| `prospeccion-ia.models.ts` | Contratos del servicio que usan las pantallas |
| `prospeccion-ia.textos.ts` | Textos de los `lbl*`, estados, avisos de borrador y formatos (DD/MM/AAAA, números es-CO) |
| `panel/` | Panel (6.1): Hoy, embudo por cohorte con tooltip, tasas y créditos del mes |
| `busqueda/` | Búsqueda y Campañas (3.2): frase a filtros con Gemini, filtros editables, vista previa (0 créditos), ejecutar o crear campaña diaria o semanal, avance en vivo, historial con activar o pausar campañas y drawer con qué pasó con cada persona |
| `bandeja/` | Bandeja (5.1): lista de 20 filas, drawer de revisión (editar como versión nueva, redactar de nuevo, descartar con motivo, aprobar) e historial de versiones |
| `configuracion/` | Configuración (6.2): pantalla completa por secciones (límites, qué vendemos, cargos, sectores, tamaño, ubicación, señales, exclusiones, puntaje y mensaje base), historial de versiones con drawer y "Cargar en el Formulario", y el perfil en JSON en Avanzado. `perfil-formulario.ts` pasa el JSON al formulario y de vuelta sin perder las claves que no conoce (con su spec) |

**Bootstrap y los estilos:** el shell carga Bootstrap global, y Bootstrap usa los mismos nombres de clase que el
estándar (`.btn`, `.alert`, `.tooltip`). Por eso en el MF esas clases llevan prefijo: `pia-btn`, `pia-alert`,
`pia-label`, `pia-tooltip`. Las fuentes Poppins, Inter y Ubuntu Sans Mono las carga el componente; Material Symbols
Outlined ya la carga el shell.

## Permisos

El backend valida cada acción (2.9). La pantalla solo oculta lo que el usuario no puede hacer, según las acciones del
ítem de menú `/home/prospeccion-ia` en el permission-service: **Crear** muestra buscar y manejar campañas; **Editar**
muestra aprobar, editar, descartar y redactar de nuevo. Guardar la configuración sale del ítem propuesto
`/home/prospeccion-ia/configuracion` (acción **Editar**, operación CONFIGURAR). Si el contexto de seguridad o ese ítem no
están, muestra los botones y el backend decide: si responde 403, la pestaña pasa a solo lectura con un mensaje claro.

## Probarlo en local

Necesita el CRM completo (Terminal > Run Task > "CRM completo"), porque la sesión y el token salen del shell:

1. Reiniciar `msa-prospeccion` (8099) para que tome la 2.9 y la 3.4.
2. En `mf-cotizaciones` y `gtc-crm-root`: `npm.cmd ci --legacy-peer-deps` solo si faltan dependencias, y
   `npm.cmd run start:local`.
3. Abrir `http://localhost:4200`, iniciar sesión y entrar a **Prospección IA** en el menú. No abrir `4201` directo:
   el MF solo no tiene `HttpClient` ni la sesión del shell.
4. El rol de su usuario debe tener operaciones en msa-prospeccion (2.9). En local, `ADMINISTRADOR` tiene todas. Si
   su rol es otro, la pantalla muestra "No tiene permiso": agréguelo en `application-local.yml`
   (`prospeccion.security.operations-by-role`) y reinicie el servicio.

Prueba hecha el 2026-11-24 (MF en 4201 con un gateway de prueba hacia el servicio local): panel con datos de una
campaña, bandeja con 9 borradores, drawer de revisión y aprobación de punta a punta (el borrador pasó a la cola con
su turno). Búsqueda (2026-11-24): frase a filtros, vista previa con 37 personas, campaña semanal ejecutada desde la
pantalla con avance en vivo y drawer de resultados por persona. Configuración (2026-11-24): el perfil aprobado pasa al
formulario y de vuelta idéntico; la validación marca dominios mal escritos, rangos de puntaje y máximos que no suman
100; guardar creó la versión #4 (perfil v2) y "Cargar en el Formulario" de la #3 creó la #5 igual a la #3 (perfil v3).
`ng build` de desarrollo y de producción sin errores.

## Pendiente

- Ítem de menú "Configuración de Prospección" en Seguridad Interna (1.13) para que el botón Guardar se oculte a quien
  no puede configurar. Mientras no exista, el backend responde 403 y la pestaña queda de solo lectura.
- Ítem de menú y acciones reales en Seguridad Interna (1.13). El mock local usa el assignmentId 128.
- Revisar con QA la pantalla dentro del CRM real (7.2).
