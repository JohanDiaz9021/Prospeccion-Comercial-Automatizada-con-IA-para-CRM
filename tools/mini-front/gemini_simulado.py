"""Gemini simulado para ver el flujo en local SIN llave de Gemini. Solo desarrollo: nunca se despliega.

Responde como la API de Gemini (POST /v1beta/models/<modelo>:generateContent) a las tres cosas que hoy pide el
servicio, con reglas fijas en lugar de IA:
  * Búsqueda (3.2): convierte la frase en filtros de Apollo buscando cargos, sectores, ciudades y tamaños conocidos.
  * Calificación (3.5): puntos por criterio comparando el contacto con el perfil de interés que recibe.
  * Redacción (4.3): llena el mensaje base que recibe con los datos del contacto.
El modelo que devuelve es "gemini-SIMULADO-local", así el mini front y la base muestran que no es Gemini real.

Lo arranca tools/crm-local (tarea "Prospección: Gemini simulado") y el servicio lo usa solo si GEMINI_API_KEY está
vacía en el .env. Solo escucha en 127.0.0.1.

Uso: python tools/mini-front/gemini_simulado.py [--puerto 8771]
"""
import argparse
import json
import re
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODELO = "gemini-SIMULADO-local"
NIVELES = ("high", "medium", "low", "excluded")

SECTORES_ES = {
    "hospital & health care": "salud", "medical practice": "salud", "retail": "comercio",
    "logistics & supply chain": "logística", "transportation/trucking/railroad": "transporte",
    "construction": "construcción", "financial services": "servicios financieros", "insurance": "seguros",
    "higher education": "educación", "education management": "educación", "food & beverages": "alimentos",
    "food production": "alimentos", "marketing & advertising": "publicidad",
}


def sin_tildes(texto):
    texto = unicodedata.normalize("NFD", (texto or "").lower())
    return "".join(c for c in texto if unicodedata.category(c) != "Mn").strip()


# ---------------------------------------------------------------- calificación (3.5)

def puntos_por_nivel(criterio, nivel):
    if nivel is None:
        return criterio.get("missingPoints", 0)
    if nivel == "excluded":
        return 0
    rango = criterio.get(nivel) or [0, 0]
    return rango[1] - (rango[1] - rango[0]) // 3


def nivel_de_lista(valores, por_nivel):
    """Nivel más bajo entre los valores que aparecen en el perfil (regla del prompt)."""
    encontrados = []
    for valor in valores or []:
        for i, nivel in enumerate(NIVELES):
            if sin_tildes(valor) in [sin_tildes(v) for v in por_nivel(nivel)]:
                encontrados.append(i)
    return NIVELES[max(encontrados)] if encontrados else None


def calificar(perfil, contacto):
    criterios = {c["key"]: c for c in perfil.get("scoring", {}).get("criteria", [])}
    faltan, partes, puntos = [], [], {}

    titulo = sin_tildes(contacto.get("title"))
    nivel_titulo = None
    if titulo:
        for nivel in NIVELES:
            lista = [sin_tildes(t) for t in perfil.get("titles", {}).get(nivel, {}).get("titles", [])]
            # Palabras completas: "director" no debe encajar con "CTO".
            if any(t and re.search(r"\b" + re.escape(t) + r"\b", titulo) for t in lista):
                nivel_titulo = nivel
                break
        nivel_titulo = nivel_titulo or "low"
    else:
        faltan.append("title")
    puntos["title"] = puntos_por_nivel(criterios.get("title", {}), nivel_titulo)
    partes.append(f"cargo {nivel_titulo or 'sin dato'}")

    def industrias(nivel):
        return [i for s in perfil.get("sectors", {}).get(nivel, []) for i in s.get("apolloIndustries", [])]
    nivel_sector = nivel_de_lista(contacto.get("possibleIndustries"), industrias)
    if not contacto.get("possibleIndustries"):
        faltan.append("sector")
    elif nivel_sector is None:
        nivel_sector = "low"
    puntos["sector"] = puntos_por_nivel(criterios.get("sector", {}), nivel_sector)
    partes.append(f"sector {nivel_sector or 'sin dato'}")

    nivel_tamano = nivel_de_lista(contacto.get("possibleEmployeeRanges"),
                                  lambda n: perfil.get("companySizes", {}).get(n, []))
    if not contacto.get("possibleEmployeeRanges"):
        faltan.append("companySize")
    puntos["companySize"] = puntos_por_nivel(criterios.get("companySize", {}), nivel_tamano)
    partes.append(f"tamaño {nivel_tamano or 'sin dato'}")

    ubicaciones = contacto.get("possiblePersonLocations") or contacto.get("possibleOrganizationLocations")
    nivel_ubicacion = nivel_de_lista(ubicaciones, lambda n: perfil.get("locations", {}).get(n, []))
    if not ubicaciones:
        faltan.append("location")
    elif nivel_ubicacion is None:
        nivel_ubicacion = "low" if any("colombia" in sin_tildes(u) for u in ubicaciones) else "excluded"
    puntos["location"] = puntos_por_nivel(criterios.get("location", {}), nivel_ubicacion)
    partes.append(f"ubicación {nivel_ubicacion or 'sin dato'}")

    senales = contacto.get("signals") or []
    if senales:
        maximo = criterios.get("signals", {}).get("maxPoints", 20)
        puntos["signals"] = min(maximo, 8 * len(senales))
    else:
        faltan.append("signals")
        puntos["signals"] = criterios.get("signals", {}).get("missingPoints", 0)

    return {
        "ref": contacto.get("ref"),
        "criteria": puntos,
        "penalty": 0,
        "missingData": faltan,
        "reason": "[SIMULADO, no es Gemini] " + ", ".join(partes) + ".",
    }


# ---------------------------------------------------------------- redacción (4.3)

def servicio_para(company, cargo):
    servicios = company.get("services") or []
    cargo = sin_tildes(cargo)
    pistas = [("tecnolog", "ti"), ("sistemas", "ti"), ("ti", "ti"), ("operacion", "operaciones"),
              ("financ", "financieras"), ("general", "gerencia general"), ("logistic", "operaciones")]
    for palabra, busca in pistas:
        if re.search(r"\b" + palabra, cargo):
            for s in servicios:
                if busca in sin_tildes(s.get("bestFor")):
                    return s
    return servicios[0] if servicios else None


def redactar(mensaje, company, contacto):
    nombre = contacto.get("firstName")
    empresa = contacto.get("organizationName") or "su empresa"
    cargo = contacto.get("title")
    sector = SECTORES_ES.get(sin_tildes(contacto.get("industry")), contacto.get("industry"))
    valores = {"empresa": empresa, "cargo": (cargo or "").lower() or None, "ciudad": contacto.get("city"),
               "sector": sector}

    lineas = mensaje.strip().splitlines()
    asunto = ""
    if lineas and sin_tildes(lineas[0]).startswith("asunto"):
        asunto = lineas.pop(0).split(":", 1)[1].strip()

    servicio = servicio_para(company, cargo)
    personalizadas = 0
    salida = []
    for linea in lineas:
        if "[[" in linea:
            personalizadas += 1
            if personalizadas == 1 and servicio:
                quien = f"En su rol de {cargo.lower()} en {empresa}" if cargo else f"En {empresa}"
                problema = servicio.get("solves", "").rstrip(".").lower()
                linea = f"{quien}, es común encontrarse con {problema}."
            else:
                continue
        if not nombre:
            linea = linea.replace(", {{nombre}}", "").replace("{{nombre}}", "usted")
        linea = linea.replace("{{nombre}}", nombre or "")
        for clave, valor in valores.items():
            linea = linea.replace("{{%s}}" % clave, valor or "")
        salida.append(linea)
    for clave, valor in valores.items():
        asunto = asunto.replace("{{%s}}" % clave, valor or "")
    asunto = asunto.replace("{{nombre}}", nombre or "")

    html = []
    for bloque in re.split(r"\n\s*\n", "\n".join(salida).strip()):
        filas = [f.strip() for f in bloque.splitlines() if f.strip()]
        if filas and all(f.startswith("- ") for f in filas):
            html.append("<ul>" + "".join(f"<li>{escapar(f[2:])}</li>" for f in filas) + "</ul>")
        elif filas:
            html.append("<p>" + "<br>".join(escapar(f) for f in filas) + "</p>")
    return {"subject": asunto, "bodyHtml": "\n".join(html)}


def escapar(texto):
    return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# ---------------------------------------------------------------- búsqueda (3.2)

# (palabra en la frase sin tildes, cargos en español e inglés, niveles de Apollo)
CARGOS = [
    (("ti", "sistemas", "tecnologia", "it", "informatica"),
     ["Gerente de TI", "Director de TI", "Director de Tecnología", "Jefe de Sistemas", "IT Manager", "CIO", "CTO"],
     ["director", "manager", "head", "c_suite"]),
    (("operaciones",), ["Gerente de Operaciones", "Director de Operaciones", "Operations Manager", "COO"],
     ["director", "manager", "c_suite"]),
    (("financiero", "finanzas"), ["Gerente Financiero", "Director Financiero", "Finance Manager", "CFO"],
     ["director", "manager", "c_suite"]),
    (("logistica",), ["Gerente de Logística", "Logistics Manager"], ["manager", "director"]),
    (("general", "ceo", "presidente", "dueno", "dueños", "duenos"),
     ["Gerente General", "Presidente", "CEO", "General Manager", "Fundador", "Founder"],
     ["owner", "founder", "c_suite"]),
]
SECTORES = [
    (("clinica", "hospital", "ips", "salud", "laboratorio"), ["hospital & health care", "medical practice"]),
    (("tienda", "retail", "comercio", "supermercado"), ["retail", "supermarkets"]),
    (("transporte", "logistica", "transportadora"), ["logistics & supply chain", "transportation/trucking/railroad"]),
    (("constructora", "construccion"), ["construction"]),
    (("colegio", "universidad", "educacion"), ["education management", "higher education"]),
    (("aseguradora", "seguros", "fintech", "cooperativa"), ["insurance", "financial services"]),
    (("manufactura", "fabrica", "industria"), ["machinery", "food production"]),
]
CIUDADES = {"bogota": "Bogota, Colombia", "medellin": "Medellin, Colombia", "cali": "Cali, Colombia",
            "barranquilla": "Barranquilla, Colombia", "bucaramanga": "Bucaramanga, Colombia",
            "cartagena": "Cartagena, Colombia", "pereira": "Pereira, Colombia"}
TAMANOS = {"pequenas": ["11,50"], "medianas": ["51,200", "201,500"], "grandes": ["501,1000", "1001,5000"]}


def interpretar(datos):
    texto = sin_tildes(datos.get("query", ""))
    palabras = set(re.findall(r"[a-z0-9]+", texto))
    cargos, niveles, sectores, ciudades, rangos = [], [], [], [], []
    for claves, titulos, nivs in CARGOS:
        if palabras & set(claves):
            cargos += titulos
            niveles += nivs
    if not cargos and palabras & {"gerentes", "gerente", "directores", "director", "jefes", "jefe"}:
        cargos, niveles = ["Gerente General", "Director", "General Manager"], ["director", "manager"]
    for claves, industrias in SECTORES:
        if any(p.startswith(c) for p in palabras for c in claves):
            sectores += industrias
    for clave, ciudad in CIUDADES.items():
        if clave in palabras:
            ciudades.append(ciudad)
    for clave, valores in TAMANOS.items():
        if clave in palabras:
            rangos += valores
    clave_sap = ["SAP"] if "sap" in palabras else []
    entendida = bool(cargos or sectores or clave_sap)
    validos = set(datos.get("allowedSeniorities") or [])
    return {
        "understood": entendida,
        "personTitles": list(dict.fromkeys(cargos)),
        "personSeniorities": [n for n in dict.fromkeys(niveles) if not validos or n in validos],
        "organizationLocations": ciudades or ["Colombia"],
        "organizationIndustries": list(dict.fromkeys(sectores)),
        "organizationNumEmployeesRanges": rangos,
        "keywords": clave_sap,
    }


# ---------------------------------------------------------------- servidor

class Manejador(BaseHTTPRequestHandler):

    def log_message(self, formato, *args):
        pass

    def do_GET(self):
        if self.path == "/estado":
            return self.enviar(200, {"simulado": True, "modelo": MODELO})
        self.enviar(404, {"error": "no existe"})

    def do_POST(self):
        cuerpo = json.loads(self.leer_cuerpo().decode("utf-8"))
        datos = json.loads(cuerpo["contents"][0]["parts"][0]["text"])
        if "query" in datos and "allowedSeniorities" in datos:
            respuesta = interpretar(datos)
            print("Búsqueda simulada interpretada", flush=True)
        elif "contacts" in datos:
            respuesta = {"results": [calificar(datos.get("profile", {}), c) for c in datos["contacts"]]}
            print(f"Calificación simulada de {len(datos['contacts'])} contacto(s)", flush=True)
        elif "baseMessage" in datos:
            respuesta = redactar(datos["baseMessage"], datos.get("company", {}), datos.get("contact", {}))
            print("Redacción simulada de un borrador", flush=True)
        else:
            return self.enviar(400, {"error": {"message": "El simulador solo sabe interpretar búsquedas, calificar y redactar"}})
        self.enviar(200, {
            "candidates": [{"content": {"parts": [{"text": json.dumps(respuesta, ensure_ascii=False)}]},
                            "finishReason": "STOP"}],
            "usageMetadata": {"promptTokenCount": 0, "candidatesTokenCount": 0},
            "modelVersion": MODELO,
        })

    def leer_cuerpo(self):
        # El cliente de Java envía el cuerpo por partes (chunked), sin Content-Length.
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            partes = []
            while True:
                tam = int(self.rfile.readline().strip().split(b";")[0], 16)
                if tam == 0:
                    self.rfile.readline()
                    return b"".join(partes)
                partes.append(self.rfile.read(tam))
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def enviar(self, estado, objeto):
        datos = json.dumps(objeto, ensure_ascii=False).encode("utf-8")
        self.send_response(estado)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)


def main():
    parser = argparse.ArgumentParser(description="Gemini simulado (solo desarrollo local)")
    parser.add_argument("--puerto", type=int, default=8771)
    puerto = parser.parse_args().puerto
    print(f"Gemini simulado en http://127.0.0.1:{puerto} (no es IA: reglas fijas). Ctrl+C para salir.", flush=True)
    try:
        ThreadingHTTPServer(("127.0.0.1", puerto), Manejador).serve_forever()
    except KeyboardInterrupt:
        print("Listo.")


if __name__ == "__main__":
    main()
