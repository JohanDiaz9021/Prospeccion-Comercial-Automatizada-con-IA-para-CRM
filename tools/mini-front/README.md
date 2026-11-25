# Mini front local

Pantallas sencillas para ver y probar el servicio mientras no existe la pantalla real en `mf-cotizaciones`. **Solo para desarrollo**: no se entrega al CRM ni se despliega.

| Pestaña | Qué permite | Tarea |
|---|---|---|
| Resumen | Qué tareas están listas, si hay llaves y dónde probar cada una | — |
| Configuración | Ver y guardar el perfil de interés, el mensaje base, el umbral y los límites de envío. Botón para cargar los documentos aprobados de `docs/negocio`. Cada guardado crea una versión nueva | 6.2 |
| Probar calificación | Escribir hasta 10 contactos **inventados** y ver puntaje, categoría, puntos por criterio y razón. Puede usar el perfil guardado o el de la pestaña Configuración sin guardarlo | 3.5 |
| Panel | Lo que espera hoy (por revisar, en cola, campañas activas), el embudo y las tasas de las búsquedas del periodo (filtro por fechas y campaña) y los créditos del mes | 6.1 |
| Búsqueda y Campañas | Escribir a quién busca, revisar los filtros que propone Gemini, ver la vista previa (0 créditos) y ejecutar: el avance muestra el embudo (Apollo, nuevas, calificadas, contactos con borrador). Historial, activar o pausar la campaña diaria, "Repetir Campañas Ahora" y borrar los datos de prueba | 3.2 |
| Probar redacción | Escribir un contacto **inventado** y ver el borrador del correo: asunto, vista del correo, texto plano, palabras y avisos. Puede usar el mensaje base guardado o el de la pestaña Configuración sin guardarlo | 4.3 |
| Probar Correo | Buscar personas en Apollo (gratis) y conseguir el correo de una, o probar **solo con Hunter** escribiendo nombre, apellido y dominio (sirve sin plan pago de Apollo). Gasta créditos reales y pide confirmación. Solo existe con el perfil local | 4.1 y 4.2 |
| Bandeja de Borradores | Revisar, editar (versión nueva), redactar de nuevo, descartar con motivo y aprobar; al aprobar entra a la cola. En un aprobado, "Probar el Seguimiento" lo marca como enviado y simula respuestas, rebotes, quejas, "fuera de la oficina" y pedidos de baja (5.4). "Nuevo Borrador de Prueba" crea un contacto inventado [DEMO] (solo local) | 5.1 |
| Créditos | Consumo del mes de Apollo y Hunter frente al presupuesto, tope por campaña, costo estimado y saldo de Hunter | 4.2 |
| Cola de Envío | Si ahora se puede enviar según la Ley 2300, cuántos van hoy y cuántos hay en cola, simulación de turnos y festivos del año. "Probar el Envío": Enviar Ya y Leer el Buzón con Mailpit (solo local) | 5.3, 5.2 |

## Gemini simulado

Sin `GEMINI_API_KEY` en el `.env`, la calificación y la redacción no podrían probarse. Para ver el flujo completo,
`tools\crm-local` arranca el servicio contra [gemini_simulado.py](gemini_simulado.py) (puerto 8771): responde como la API de
Gemini, pero con **reglas fijas, no con IA**. Califica comparando el contacto con el perfil de interés y redacta llenando
el mensaje base con los datos del contacto.

- Todo lo simulado queda marcado: chip **"Gemini SIMULADO"** arriba, aviso amarillo en cada pestaña, modelo
  `gemini-SIMULADO-local` y la razón de la calificación empieza con `[SIMULADO, no es Gemini]`.
- Sirve para ver pantallas, reglas, avisos y errores, **no** la calidad de la IA.
- Para pasar a Gemini real: poner `GEMINI_API_KEY` en el `.env` y reiniciar la tarea **CRM: msa-prospeccion (8099)**.

## Apollo simulado

El plan gratis de Apollo no incluye la búsqueda de personas ni el enriquecimiento. Sin `APOLLO_API_KEY` en el `.env`,
`tools\crm-local` arranca el servicio contra [apollo_simulado.py](apollo_simulado.py) (puerto 8772, tarea
**Prospección: Apollo simulado (8772)**): responde como la API de Apollo con personas y empresas **inventadas** que
cumplen los filtros.

- Empresas con "(Simulada)" en el nombre y dominios `.example` (reservados: nunca existen).
- Mismos filtros, mismas personas: se ve que la segunda ejecución solo procesa las nuevas.
- Una de cada cinco tiene un cargo que no encaja (para ver a la IA descartarla), algunas no tienen correo y algunas
  comparten empresa (para ver que se escribe a una sola persona por empresa).
- Todos los correos vienen verificados y quien no tiene correo tampoco tiene dominio: el servicio nunca llama a Hunter
  con datos inventados.
- Para pasar a Apollo real: poner `APOLLO_API_KEY` en el `.env` y reiniciar la tarea **CRM: msa-prospeccion (8099)**.

## Cómo funciona

```
Navegador (localhost:4300) -> servidor.py (agrega un token de prueba) -> servicio (localhost:8099) -> MySQL (Docker)
                                                                              \-> Gemini (si hay llave)
```

`servidor.py` sirve las páginas de `web/` y reenvía `/msa-prospeccion/*` al servicio con un token firmado con la `JWT_SECRET_KEY` inventada del `.env` (igual que `tools/token-local/generar_token.py`). Solo escucha en `127.0.0.1`.

No usa el gateway ni Eureka: en el perfil local el servicio corre solo. Para ir por el gateway: `--backend http://localhost:8090`.

## Requisitos (una sola vez)

1. Docker Desktop abierto.
2. Java 21 y Python 3.
3. `.env` en la raíz (copia de `.env.example`) con `JWT_SECRET_KEY`. Para probar la calificación, también `GEMINI_API_KEY` (llave de la capa gratuita; **solo datos inventados**).
4. La base local con sus tablas: pasos 2 y 2b de "Puesta en marcha local" del [README principal](../../README.md).

## Arrancar

**En VS Code (recomendado):** `Ctrl + Shift + P` → **Tasks: Run Task** → **Prospección IA: mini front**. Abre una terminal
por pieza (MySQL, Gemini simulado si no hay llave, `msa-prospeccion` y el mini front) y al final el navegador en
`http://localhost:4300`. Si ya corre el CRM completo, basta con **Prospección: mini front (4300)**.

Con el script, desde la raíz del repo, en PowerShell (sin Gemini simulado):

```powershell
powershell -ExecutionPolicy Bypass -File tools\mini-front\iniciar.ps1
```

El script levanta MySQL, abre el servicio en otra ventana (perfil `local`), espera a que responda y abre `http://localhost:4300`.

A mano, en tres terminales:

```powershell
docker compose -f docker-compose.local.yml up -d --wait
$env:SPRING_PROFILES_ACTIVE = "local"; .\gradlew.bat bootRun
python tools\mini-front\servidor.py
```

Para apagar: Ctrl+C en el mini front, cerrar la ventana del servicio y `docker compose -f docker-compose.local.yml down`.

Atajos: `#configuracion`, `#correo`, `#bandeja`, `#creditos`, `#cola`, `http://localhost:4300/#calificar` abre la segunda pestaña y `#calificar-ejemplos` la abre con los 5 ejemplos del documento ya cargados. `#redactar` abre la tercera y `#redactar-ejemplo` la abre con el contacto del ejemplo de `mensaje-base.md`.

## Problemas comunes

| Lo que se ve | Causa y solución |
|---|---|
| "Servicio apagado" | El servicio no está corriendo o falló al arrancar: revise su ventana |
| "El token no es válido" | La `JWT_SECRET_KEY` cambió después de arrancar el servicio: reinícielo |
| "El tenant del token no está habilitado" | `LOCAL_TENANT_CODE` no está en `PROSPECCION_TENANTS_ENABLED` del `.env` |
| "El usuario del token no existe" | Falta el usuario 1 en `sc_user`: ejecute `tools/db-local/crm-stubs.sql` |
| "Falta GEMINI_API_KEY" o "Gemini no respondió" | Agregue la llave al `.env` y **reinicie el servicio** (la lee al arrancar). Si ya está, puede ser el límite por minuto de la capa gratuita |
| "Tiene marcas [validar]" | El servicio no deja guardar textos con esa marca: quítela |
| "Gemini no logró un borrador que cumpla las reglas" | Gemini dejó marcas sin llenar o quitó la firma dos veces seguidas. Intente de nuevo; si se repite, revise el mensaje base |

## Límites

- No se ve como el CRM (sin su menú ni estilos). La pantalla real va en `mf-cotizaciones` con la tarea 3.2.
- No hay búsqueda en Apollo todavía: falta la llave y la tarea 3.2.
- Sin `GEMINI_API_KEY` local, el servicio busca la llave en AWS SSM; en local no hay credenciales de AWS y la pantalla muestra "Gemini no respondió". Ponga la llave en el `.env` y reinicie el servicio.
