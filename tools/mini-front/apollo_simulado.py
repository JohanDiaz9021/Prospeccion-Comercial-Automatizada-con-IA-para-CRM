"""Apollo simulado para probar la búsqueda (3.2) en local SIN plan pago de Apollo. Solo desarrollo: nunca se despliega.

Responde como la API de Apollo a las dos rutas que usa el servicio, con personas y empresas INVENTADAS:
  * POST /api/v1/mixed_people/api_search  (People API Search, 0 créditos): personas que cumplen los filtros.
  * POST /api/v1/people/match?id=...      (People Enrichment): la persona completa con su correo.

Las personas salen de los filtros (cargos, sectores, ciudades y tamaños pedidos) y son siempre las mismas para los
mismos filtros: así se puede ver que una segunda ejecución no vuelve a procesar a las que ya vio. Como el Apollo
real (que busca cargos parecidos), una de cada cinco tiene un cargo que no encaja, para ver a la IA descartarla. Todas las empresas
usan dominios .example (reservados: nunca existen) y todos los correos vienen "verified", para que el servicio no
llame a Hunter con datos inventados. Unas pocas personas no tienen correo (para ver el caso SIN_CORREO) y algunas
comparten empresa (para ver que solo se escribe a una persona por empresa).

Lo arranca tools/crm-local (tarea "Prospección: Apollo simulado") y el servicio lo usa solo si APOLLO_API_KEY está
vacía en el .env. Solo escucha en 127.0.0.1.

Uso: python tools/mini-front/apollo_simulado.py [--puerto 8772]
"""
import argparse
import hashlib
import json
import os
import random
import re
import tempfile
import threading
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

REGISTRO = os.path.join(tempfile.gettempdir(), "apollo_simulado_personas.json")
CANDADO = threading.Lock()

NOMBRES = ["Laura", "Andrés", "Camila", "Santiago", "Valentina", "Felipe", "Daniela", "Julián", "Mariana", "Sebastián",
           "Paula", "Nicolás", "Carolina", "Mateo", "Natalia", "Alejandro", "Juliana", "Diego", "Manuela", "Esteban"]
APELLIDOS = ["Gómez", "Rodríguez", "Martínez", "López", "Hernández", "Ramírez", "Torres", "Castro", "Vargas", "Rojas",
             "Moreno", "Jiménez", "Ortiz", "Silva", "Mendoza", "Cárdenas", "Pardo", "Salazar", "Restrepo", "Quintero"]
NOMBRES_EMPRESA = ["Andina", "del Valle", "Horizonte", "La Sabana", "Montaña Azul", "Santa Lucía", "El Roble",
                   "Nueva Granada", "Los Cerezos", "Altamira", "San Rafael", "La Esperanza", "Puerto Claro",
                   "Bosque Verde", "Las Palmas", "Cordillera", "Río Grande", "La Colina", "Mirador", "Sol Naciente"]
PREFIJOS = {"hospital & health care": "Clínica", "medical practice": "Centro Médico", "retail": "Almacenes",
            "supermarkets": "Supermercados", "logistics & supply chain": "Logística",
            "transportation/trucking/railroad": "Transportes", "construction": "Constructora",
            "education management": "Colegio", "higher education": "Universidad", "insurance": "Seguros",
            "financial services": "Cooperativa", "machinery": "Industrias", "food production": "Alimentos"}
INDUSTRIAS = ["hospital & health care", "retail", "logistics & supply chain", "construction", "machinery",
              "insurance", "education management"]
CARGOS = ["Gerente General", "Director de Tecnología", "Gerente de TI", "Director de Operaciones", "IT Manager"]
# Apollo busca cargos parecidos (include_similar_titles), así que también trae algunos que no encajan con el perfil:
# así se ve a la IA descartarlos antes de gastar créditos.
CARGOS_PARECIDOS = ["Analista de Sistemas", "Asistente Administrativo", "Auxiliar de TI", "Practicante de Tecnología",
                    "Ejecutivo de Ventas"]
NIVELES = {"gerente general": "c_suite", "ceo": "c_suite", "cto": "c_suite", "cio": "c_suite", "coo": "c_suite",
           "cfo": "c_suite", "director": "director", "gerente": "manager", "manager": "manager", "jefe": "head",
           "head": "head", "fundador": "founder", "founder": "founder", "presidente": "c_suite"}


def ascii_min(texto):
    texto = unicodedata.normalize("NFD", texto.lower())
    return "".join(c for c in texto if unicodedata.category(c) != "Mn")


def slug(texto):
    return re.sub(r"[^a-z0-9]+", "", ascii_min(texto))


def nivel_de(cargo):
    c = ascii_min(cargo)
    for palabra, nivel in NIVELES.items():
        if palabra in c:
            return nivel
    return "senior"


def empleados_de(rango, azar):
    desde, _, hasta = rango.partition(",")
    desde = int(desde or 1)
    hasta = int(hasta) if hasta else desde * 2
    return azar.randint(desde, hasta)


def personas_para(filtros):
    """Siempre las mismas personas para los mismos filtros (sin contar página ni tamaño)."""
    clave = json.dumps({k: sorted(v) for k, v in sorted(filtros.items())}, ensure_ascii=False)
    semilla = int(hashlib.sha256(clave.encode("utf-8")).hexdigest()[:12], 16)
    azar = random.Random(semilla)
    total = 30 + semilla % 31
    cargos = filtros.get("person_titles[]") or CARGOS
    industrias = filtros.get("organization_industries[]") or INDUSTRIAS
    ciudades = [c.split(",")[0].strip() for c in filtros.get("organization_locations[]", []) if c.strip()]
    ciudades = [c for c in ciudades if c.lower() != "colombia"] or ["Bogota", "Medellin", "Cali"]
    rangos = filtros.get("organization_num_employees_ranges[]") or ["51,200", "201,500"]
    excluidos = {d.lower().removeprefix("www.") for d in filtros.get("not_organization_websites_list[]", [])}

    empresas = []
    for i in range(max(8, int(total * 0.8))):
        industria = azar.choice(industrias)
        nombre = f"{PREFIJOS.get(industria, 'Grupo')} {NOMBRES_EMPRESA[(semilla + i) % len(NOMBRES_EMPRESA)]}"
        nombre += f" {i + 1} (Simulada)"
        dominio = slug(nombre.replace("(Simulada)", "")) + ".example"
        empresas.append({"id": "org" + hashlib.sha1((clave + dominio).encode()).hexdigest()[:14], "name": nombre,
                         "primary_domain": dominio, "industry": industria,
                         "estimated_num_employees": empleados_de(azar.choice(rangos), azar),
                         "city": azar.choice(ciudades), "linkedin_url": f"https://linkedin.example/company/{slug(nombre)}"})

    personas = []
    for i in range(total):
        empresa = empresas[azar.randrange(len(empresas))]
        if empresa["primary_domain"] in excluidos:
            continue
        nombre, apellido = azar.choice(NOMBRES), azar.choice(APELLIDOS)
        cargo = azar.choice(CARGOS_PARECIDOS) if i % 5 == 2 else azar.choice(cargos)
        tiene_correo = azar.random() < 0.85
        personas.append({
            "id": "sim" + hashlib.sha1(f"{clave}|{i}".encode("utf-8")).hexdigest()[:20],
            "first_name": nombre, "last_name": apellido, "title": cargo, "seniority": nivel_de(cargo),
            "linkedin_url": f"https://linkedin.example/in/{slug(nombre + apellido)}{i}",
            "city": empresa["city"], "has_email": tiene_correo,
            "email": f"{slug(nombre)}.{slug(apellido)}@{empresa['primary_domain']}" if tiene_correo else None,
            "organization": empresa,
        })
    return personas


def guardar(personas):
    with CANDADO:
        try:
            registro = json.load(open(REGISTRO, encoding="utf-8"))
        except (OSError, ValueError):
            registro = {}
        registro.update({p["id"]: p for p in personas})
        json.dump(registro, open(REGISTRO, "w", encoding="utf-8"), ensure_ascii=False)


def buscar_guardada(persona_id):
    with CANDADO:
        try:
            return json.load(open(REGISTRO, encoding="utf-8")).get(persona_id)
        except (OSError, ValueError):
            return None


def para_busqueda(p):
    """Como People API Search: sin apellido completo, correo ni dominio."""
    a = p["last_name"]
    return {"id": p["id"], "first_name": p["first_name"], "last_name_obfuscated": a[0] + "***" + a[-1],
            "title": p["title"], "has_email": p["has_email"], "has_city": True, "has_state": True, "has_country": True,
            "has_direct_phone": "No",
            "organization": {"name": p["organization"]["name"], "has_industry": True, "has_employee_count": True,
                             "has_city": True, "has_phone": False}}


def para_enriquecimiento(p):
    persona = {k: p[k] for k in ("id", "first_name", "last_name", "title", "seniority", "linkedin_url", "city")}
    persona["email"] = p["email"]
    persona["email_status"] = "verified" if p["email"] else None
    org = dict(p["organization"])
    if not p["email"]:
        # Sin dominio el servicio no llama a Hunter con una empresa inventada.
        org["primary_domain"] = None
    persona["organization"] = org
    return persona


class Manejador(BaseHTTPRequestHandler):

    def log_message(self, formato, *args):
        pass

    def do_GET(self):
        if self.path == "/estado":
            return self.enviar(200, {"simulado": True})
        self.enviar(404, {"error": "no existe"})

    def do_POST(self):
        self.leer_cuerpo()
        url = urlparse(self.path)
        params = parse_qs(url.query)
        if not self.headers.get("x-api-key"):
            return self.enviar(401, {"error": "Falta x-api-key"})
        if url.path.endswith("/mixed_people/api_search"):
            pagina = int(params.pop("page", ["1"])[0])
            por_pagina = int(params.pop("per_page", ["10"])[0])
            personas = personas_para(params)
            trozo = personas[(pagina - 1) * por_pagina: pagina * por_pagina]
            guardar(trozo)
            print(f"Búsqueda simulada: página {pagina}, {len(trozo)} de {len(personas)} personas", flush=True)
            return self.enviar(200, {"total_entries": len(personas), "people": [para_busqueda(p) for p in trozo]})
        if url.path.endswith("/people/match"):
            persona = buscar_guardada(params.get("id", [""])[0])
            print("Enriquecimiento simulado: " + ("con correo" if persona and persona["email"] else "sin correo"),
                  flush=True)
            return self.enviar(200, {"person": para_enriquecimiento(persona) if persona else None})
        self.enviar(404, {"error": "El simulador solo conoce api_search y people/match"})

    def leer_cuerpo(self):
        # El cliente de Java envía el cuerpo por partes (chunked), sin Content-Length.
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            while True:
                tam = int(self.rfile.readline().strip().split(b";")[0], 16)
                if tam == 0:
                    self.rfile.readline()
                    return
                self.rfile.read(tam)
                self.rfile.readline()
        self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def enviar(self, estado, objeto):
        datos = json.dumps(objeto, ensure_ascii=False).encode("utf-8")
        self.send_response(estado)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(datos)))
        self.send_header("x-minute-requests-left", "200")
        self.end_headers()
        self.wfile.write(datos)


def main():
    parser = argparse.ArgumentParser(description="Apollo simulado (solo desarrollo local)")
    parser.add_argument("--puerto", type=int, default=8772)
    puerto = parser.parse_args().puerto
    print(f"Apollo simulado en http://127.0.0.1:{puerto} (personas y empresas inventadas). Ctrl+C para salir.",
          flush=True)
    try:
        ThreadingHTTPServer(("127.0.0.1", puerto), Manejador).serve_forever()
    except KeyboardInterrupt:
        print("Listo.")


if __name__ == "__main__":
    main()
