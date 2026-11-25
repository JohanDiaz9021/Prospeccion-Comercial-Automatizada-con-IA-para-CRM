# Colección de Bruno de Prospección IA (tarea 2.12)

Llamadas de ejemplo para probar a mano cada endpoint de `msa-prospeccion-crm` y las APIs de Apollo, Hunter y Gemini. Solo para desarrollo y con datos de prueba.

La colección está en [prospeccion-ia/](prospeccion-ia/) y son archivos de texto versionados en git. Los secretos (token y llaves) **no** están en los archivos: son variables secretas que Bruno guarda en tu equipo.

## Abrirla

1. Instala [Bruno](https://www.usebruno.com/downloads) (gratis, no pide cuenta).
2. *Open Collection* → elige la carpeta `tools/bruno/prospeccion-ia`.
3. Arriba a la derecha, elige el entorno:
   - **local**: directo al servicio (`http://localhost:8099/msa-prospeccion`).
   - **local-gateway**: a través de msa-apigateway-crm (`http://localhost:8090/msa-prospeccion`), como lo hará el frontend.

## Poner el token (cada hora)

El token de prueba vence en 60 minutos. Para generarlo y copiarlo al portapapeles:

```powershell
python tools/token-local/generar_token.py | Set-Clipboard
```

En Bruno: *Environments* → **local** → variable `token` → pegar. Todas las peticiones del servicio lo usan, porque heredan la autenticación de la colección.

Opciones útiles: `--tenant otro` (tenant no habilitado), `--sin-tenant`, `--minutos -5` (vencido), `--alg HS256` (token del login local). Ver `--help`.

## Llaves de los proveedores (carpeta 90)

En el mismo entorno, completa `apolloApiKey`, `hunterApiKey` y `geminiApiKey` con las llaves de los **planes gratis**. Son las mismas que van en el `.env` (`APOLLO_API_KEY`, `HUNTER_API_KEY`, `GEMINI_API_KEY`). Nunca uses las de QA o producción: esas viven solo en AWS SSM.

| Petición | Costo |
|---|---|
| Apollo - Buscar personas | 0 créditos |
| Apollo - Enriquecer persona | **1 crédito** si devuelve el correo |
| Hunter - Créditos disponibles | Gratis |
| Hunter - Verificar correo | **1 verificación** |
| Gemini - Respuesta JSON | Pago por tokens (centavos). En la capa gratuita, solo datos inventados |

## Qué responde hoy

| Carpeta | Respuesta esperada |
|---|---|
| 00 Salud y seguridad | Health 200; sin token o token inválido 401; header de tenant distinto 403 (directo al servicio); token válido pasa |
| 01 a 05 | 501 `lblNotImplemented` mientras el servicio esté en TODO. Sirve para confirmar ruta, seguridad y tenant |
| 06 Baja pública | Sin token; hoy 501 |

Cada petición tiene pruebas (pestaña *Tests*) que aceptan también la respuesta final (200, 400, 404...), así que la colección sigue sirviendo cuando se implemente cada fase. La pestaña *Docs* de cada petición explica qué hace.

## Correrla desde la consola

Con la app arriba (perfil local):

```powershell
$token = python tools/token-local/generar_token.py
cd tools/bruno/prospeccion-ia
npx @usebruno/cli@4.3.0 run "00 Salud y seguridad" "01 Configuracion" "02 Busqueda" "03 Prospectos" "04 Borradores" "05 Creditos y tablero" "06 Baja publica" --env local --env-var "token=$token"
```

La carpeta 90 no se incluye: llama a servicios externos y algunas peticiones gastan créditos.
