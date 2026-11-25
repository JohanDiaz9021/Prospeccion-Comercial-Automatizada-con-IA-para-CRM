"""Mini front local de Prospección IA (solo desarrollo).

Sirve las páginas de web/ y reenvía /msa-prospeccion/* al servicio agregando un token de prueba firmado con la
JWT_SECRET_KEY inventada del .env local (el mismo de tools/token-local/generar_token.py). Así el navegador no
necesita token ni hay problemas de CORS.

Solo escucha en 127.0.0.1: como agrega un token válido, no debe quedar abierto a la red.

    python tools/mini-front/servidor.py            # http://localhost:4300
    python tools/mini-front/servidor.py --help     # puerto, backend, usuario y tenant
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

AQUI = Path(__file__).resolve().parent
WEB = AQUI / "web"
RAIZ_REPO = AQUI.parent.parent
sys.path.insert(0, str(RAIZ_REPO / "tools" / "token-local"))
from generar_token import crear_token, leer_env  # noqa: E402

PREFIJO_API = "/msa-prospeccion/"
TIPOS = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
         ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".ico": "image/x-icon"}
DOCUMENTOS = {"perfil": RAIZ_REPO / "docs" / "negocio" / "perfil-de-interes.json",
              "mensaje": RAIZ_REPO / "docs" / "negocio" / "mensaje-base.txt"}


def crear_manejador(config):
    class Manejador(BaseHTTPRequestHandler):
        server_version = "MiniFrontProspeccion"

        def log_message(self, formato, *args):  # una línea corta por petición, sin cuerpos
            sys.stdout.write("  %s %s\n" % (self.command, self.path.split("?")[0]))

        def do_GET(self):
            if self.path.startswith(PREFIJO_API):
                return self.reenviar()
            if self.path == "/dev/info":
                return self.info()
            if self.path == "/dev/documentos":
                return self.documentos()
            return self.archivo()

        def do_POST(self):
            self.solo_api()

        def do_PUT(self):
            self.solo_api()

        def do_DELETE(self):
            self.solo_api()

        def do_PATCH(self):
            self.solo_api()

        def solo_api(self):
            if self.path.startswith(PREFIJO_API):
                return self.reenviar()
            self.responder(404, {"success": False, "messageKey": "lblNotFound"})

        # ---------- Archivos de web/ ----------

        def archivo(self):
            ruta = self.path.split("?")[0]
            ruta = "/index.html" if ruta == "/" else ruta
            destino = (WEB / ruta.lstrip("/")).resolve()
            if WEB not in destino.parents or not destino.is_file():
                return self.responder(404, {"success": False, "messageKey": "lblNotFound"})
            datos = destino.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", TIPOS.get(destino.suffix, "application/octet-stream"))
            self.send_header("Content-Length", str(len(datos)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(datos)

        # ---------- Datos de apoyo ----------

        def info(self):
            env = leer_env_completo()
            self.responder(200, {
                "backend": config.backend,
                "tenant": config.tenant,
                "usuario": config.sub,
                # Solo si hay llave, nunca su valor.
                "llaveGemini": bool(env.get("GEMINI_API_KEY")),
                "llaveApollo": bool(env.get("APOLLO_API_KEY")),
                "llaveHunter": bool(env.get("HUNTER_API_KEY")),
                # Sin llave, tools/crm-local arranca el servicio contra el Gemini simulado (gemini_simulado.py).
                "geminiSimulado": not env.get("GEMINI_API_KEY") and gemini_simulado_arriba(),
                # Sin llave de Apollo, la búsqueda (3.2) usa el Apollo simulado (apollo_simulado.py).
                "apolloSimulado": not env.get("APOLLO_API_KEY") and gemini_simulado_arriba(8772),
            })

        def documentos(self):
            self.responder(200, {nombre: ruta.read_text(encoding="utf-8") if ruta.exists() else None
                                 for nombre, ruta in DOCUMENTOS.items()})

        # ---------- Reenvío al servicio ----------

        def reenviar(self):
            largo = int(self.headers.get("Content-Length") or 0)
            cuerpo = self.rfile.read(largo) if largo else None
            try:
                token = crear_token(leer_env(), sub=config.sub, tenant=config.tenant)
            except ValueError as e:
                return self.responder(500, {"success": False, "messageKey": "lblMiniFrontToken", "detail": str(e)})

            peticion = urllib.request.Request(config.backend + self.path, data=cuerpo, method=self.command)
            peticion.add_header("Authorization", "Bearer " + token)
            peticion.add_header("Accept", "application/json")
            if cuerpo is not None:
                peticion.add_header("Content-Type", self.headers.get("Content-Type", "application/json"))
            try:
                with urllib.request.urlopen(peticion, timeout=180) as respuesta:
                    self.copiar(respuesta.status, respuesta.headers.get("Content-Type"), respuesta.read())
            except urllib.error.HTTPError as e:
                self.copiar(e.code, e.headers.get("Content-Type"), e.read())
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                self.responder(503, {"success": False, "messageKey": "lblServiceDown",
                                     "detail": "No responde %s (%s)" % (config.backend, getattr(e, "reason", e))})

        def copiar(self, estado, tipo, datos):
            self.send_response(estado)
            self.send_header("Content-Type", tipo or "application/json")
            self.send_header("Content-Length", str(len(datos)))
            self.end_headers()
            self.wfile.write(datos)

        def responder(self, estado, objeto):
            self.copiar(estado, "application/json; charset=utf-8", json.dumps(objeto).encode("utf-8"))

    return Manejador


def gemini_simulado_arriba(puerto=8771):
    """El simulador (Gemini 8771 o Apollo 8772) responde."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/estado" % puerto, timeout=1) as respuesta:
            return respuesta.status == 200
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return False


def leer_env_completo():
    """Todas las variables del .env (leer_env de la herramienta de tokens solo toma del sistema las del JWT)."""
    valores = leer_env()
    env = RAIZ_REPO / ".env"
    if env.exists():
        for linea in env.read_text(encoding="utf-8").splitlines():
            linea = linea.strip()
            if linea and not linea.startswith("#") and "=" in linea:
                clave, valor = linea.split("=", 1)
                valores.setdefault(clave.strip(), valor.strip())
    return valores


def main():
    env = leer_env_completo()
    parser = argparse.ArgumentParser(description="Mini front local de Prospección IA")
    parser.add_argument("--puerto", type=int, default=4300)
    parser.add_argument("--backend", default="http://localhost:%s" % env.get("SERVER_PORT", "8099"),
                        help="URL del servicio (por defecto el SERVER_PORT del .env). Para ir por el gateway: "
                             "http://localhost:8090")
    parser.add_argument("--sub", default="1", help="Usuario del token (sc_user.ID o LOGIN)")
    parser.add_argument("--tenant", default=env.get("LOCAL_TENANT_CODE", "tenant_demo"))
    parser.add_argument("--no-abrir", action="store_true", help="No abrir el navegador")
    config = parser.parse_args()
    config.backend = config.backend.rstrip("/")

    servidor = ThreadingHTTPServer(("127.0.0.1", config.puerto), crear_manejador(config))
    url = "http://localhost:%d" % config.puerto
    print("Mini front en %s -> servicio %s (tenant %s, usuario %s). Ctrl+C para salir."
          % (url, config.backend, config.tenant, config.sub), flush=True)  # flush: la tarea de VS Code espera esta línea
    if not config.no_abrir:
        webbrowser.open(url)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nListo.")


if __name__ == "__main__":
    main()
