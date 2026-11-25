"""Prueba de cobertura de Apollo y Hunter en Colombia (tarea 1.7).

Etapa A (0 créditos): corre las búsquedas de busquedas.json en Apollo People API Search y mide
cuántos gerentes trae cada una, cuántos tienen correo (has_email) y cuántos lo tienen verificado.

Etapa B (gasta créditos, opcional): toma una muestra pequeña de la etapa A y
  - con correo en Apollo: People Enrichment (1 crédito si devuelve correo) + Hunter Email Verifier;
  - sin correo en Apollo: People Enrichment (para el dominio) + Hunter Email Finder (cobra solo si encuentra).

Solo usa la biblioteca estándar de Python. Las llaves salen de las variables APOLLO_API_KEY y
HUNTER_API_KEY (o del .env de la raíz del repo). Nunca imprime llaves ni correos completos.
Los archivos con datos de personas quedan en resultados/, que está en .gitignore.

Uso: ver README.md de esta carpeta.
"""

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

AQUI = Path(__file__).resolve().parent
RAIZ_REPO = AQUI.parent.parent
RESULTADOS = AQUI / "resultados"

APOLLO_BASE = "https://api.apollo.io/api/v1"
HUNTER_BASE = "https://api.hunter.io/v2"

# Parámetros de busquedas.json que se envían a Apollo (los demás son descriptivos).
PARAMS_APOLLO = (
    "person_titles", "person_locations", "person_not_locations", "organization_locations",
    "organization_industries", "organization_num_employees_ranges", "person_seniorities", "q_keywords",
)


# ---------------------------------------------------------------- utilidades

def cargar_env():
    """Lee KEY=VALUE del .env de la raíz sin pisar variables ya definidas."""
    env = RAIZ_REPO / ".env"
    if not env.exists():
        return
    for linea in env.read_text(encoding="utf-8").splitlines():
        linea = linea.strip()
        if not linea or linea.startswith("#") or "=" not in linea:
            continue
        clave, valor = linea.split("=", 1)
        os.environ.setdefault(clave.strip(), valor.strip())


def enmascarar_correo(correo):
    if not correo or "@" not in correo:
        return ""
    usuario, dominio = correo.split("@", 1)
    return f"{usuario[:1]}***@{dominio}"


class ErrorApi(Exception):
    def __init__(self, proveedor, estado, detalle):
        super().__init__(f"{proveedor} respondió {estado}: {detalle}")
        self.estado = estado


def llamar(proveedor, metodo, url, headers, simular, reintentos=3):
    """Hace la petición y devuelve el JSON. Reintenta ante 429 y 5xx con espera creciente."""
    if simular:
        print(f"  [simulado] {metodo} {url.split('?')[0]}")
        return None
    espera = 2
    for intento in range(reintentos + 1):
        peticion = urllib.request.Request(url, method=metodo, headers=headers, data=b"" if metodo == "POST" else None)
        try:
            with urllib.request.urlopen(peticion, timeout=60) as respuesta:
                return json.loads(respuesta.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            cuerpo = e.read().decode("utf-8", errors="replace")[:300]
            if e.code in (429, 500, 502, 503, 504) and intento < reintentos:
                retry_after = e.headers.get("Retry-After")
                pausa = int(retry_after) if retry_after and retry_after.isdigit() else espera
                print(f"  {proveedor}: {e.code}, reintento en {pausa} s")
                time.sleep(pausa)
                espera *= 2
                continue
            raise ErrorApi(proveedor, e.code, cuerpo) from None
        except urllib.error.URLError as e:
            if intento < reintentos:
                time.sleep(espera)
                espera *= 2
                continue
            raise ErrorApi(proveedor, "sin conexión", str(e.reason)) from None
    return None


def apollo(ruta, params, simular):
    llave = os.environ.get("APOLLO_API_KEY", "")
    url = f"{APOLLO_BASE}{ruta}?{urllib.parse.urlencode(params, doseq=True)}"
    headers = {"x-api-key": llave, "accept": "application/json", "Cache-Control": "no-cache",
               "Content-Type": "application/json"}
    return llamar("Apollo", "POST", url, headers, simular)


def hunter(ruta, params, simular):
    llave = os.environ.get("HUNTER_API_KEY", "")
    url = f"{HUNTER_BASE}{ruta}?{urllib.parse.urlencode(params)}"
    return llamar("Hunter", "GET", url, {"X-API-KEY": llave, "accept": "application/json"}, simular)


def params_busqueda(busqueda):
    """Convierte una búsqueda de busquedas.json en query params de Apollo (listas con sufijo [])."""
    params = []
    for clave in PARAMS_APOLLO:
        valor = busqueda.get(clave)
        if valor is None:
            continue
        if isinstance(valor, list):
            params.extend((f"{clave}[]", v) for v in valor)
        else:
            params.append((clave, valor))
    return params


def porcentaje(parte, total):
    return round(100 * parte / total, 1) if total else 0.0


# ---------------------------------------------------------------- etapa A

def etapa_a(busquedas, args):
    RESULTADOS.mkdir(exist_ok=True)
    filas, personas = [], []
    for b in busquedas:
        print(f"{b['id']} · {b['descripcion']}")
        base = params_busqueda(b)
        try:
            pagina = apollo("/mixed_people/api_search", base + [("page", 1), ("per_page", 100)], args.simular)
            time.sleep(args.pausa)
            verificados = apollo("/mixed_people/api_search",
                                 base + [("contact_email_status[]", "verified"), ("page", 1), ("per_page", 1)],
                                 args.simular)
            time.sleep(args.pausa)
        except ErrorApi as e:
            print(f"  ERROR: {e}")
            if e.estado in (401, 403):
                print("  Revisa la llave de Apollo: debe ser una API key con acceso a mixed_people/api_search "
                      "(o una master key), creada desde una cuenta con correo corporativo.")
                sys.exit(1)
            filas.append({"id": b["id"], "descripcion": b["descripcion"], "error": str(e)})
            continue
        if args.simular:
            continue
        gente = pagina.get("people", []) or []
        total = pagina.get("total_entries", 0) or 0
        total_verificados = (verificados or {}).get("total_entries", 0) or 0
        con_correo = sum(1 for p in gente if p.get("has_email") is True)
        filas.append({
            "id": b["id"], "descripcion": b["descripcion"], "ciudad": b.get("ciudad", ""),
            "sector": b.get("sector", ""), "grupo_cargos": b.get("grupo_cargos", ""),
            "gerentes_encontrados": total, "muestra_revisada": len(gente),
            "muestra_con_correo": con_correo, "pct_muestra_con_correo": porcentaje(con_correo, len(gente)),
            "con_correo_verificado": total_verificados,
            "pct_verificado": porcentaje(total_verificados, total), "error": "",
        })
        for p in gente:
            personas.append({"busqueda": b["id"], "apollo_id": p.get("id"), "first_name": p.get("first_name"),
                             "title": p.get("title"), "has_email": p.get("has_email"),
                             "organizacion": (p.get("organization") or {}).get("name")})
        print(f"  {total} gerentes · muestra {len(gente)}, con correo {con_correo} · verificados {total_verificados}")

    if args.simular:
        print("\nSimulación terminada: no se llamó a ninguna API.")
        return
    campos = ["id", "descripcion", "ciudad", "sector", "grupo_cargos", "gerentes_encontrados", "muestra_revisada",
              "muestra_con_correo", "pct_muestra_con_correo", "con_correo_verificado", "pct_verificado", "error"]
    with open(RESULTADOS / "etapa_a_busquedas.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=campos, extrasaction="ignore")
        w.writeheader()
        w.writerows(filas)
    (RESULTADOS / "etapa_a_personas.json").write_text(json.dumps(personas, ensure_ascii=False, indent=1),
                                                     encoding="utf-8")
    escribir_resumen(filas, None)
    print(f"\nListo. Resumen en {RESULTADOS / 'resumen.md'}")


# ---------------------------------------------------------------- etapa B

def repartir(personas, condicion, cantidad):
    """Toma hasta `cantidad` personas que cumplan la condición, repartidas entre búsquedas."""
    por_busqueda = {}
    for p in personas:
        if condicion(p):
            por_busqueda.setdefault(p["busqueda"], []).append(p)
    elegidas, ronda = [], 0
    while len(elegidas) < cantidad and any(len(v) > ronda for v in por_busqueda.values()):
        for lista in por_busqueda.values():
            if ronda < len(lista) and len(elegidas) < cantidad:
                elegidas.append(lista[ronda])
        ronda += 1
    return elegidas


def enriquecer(apollo_id, args):
    datos = apollo("/people/match", [("id", apollo_id), ("reveal_personal_emails", "false")], args.simular)
    persona = (datos or {}).get("person") or {}
    org = persona.get("organization") or {}
    return persona, org


def etapa_b(args):
    archivo = RESULTADOS / "etapa_a_personas.json"
    if not archivo.exists():
        sys.exit("Primero corre la etapa A.")
    personas = json.loads(archivo.read_text(encoding="utf-8"))
    con = repartir(personas, lambda p: p.get("has_email") is True, args.muestra_con_correo)
    sin = repartir(personas, lambda p: p.get("has_email") is not True, args.muestra_sin_correo)
    print(f"Muestra: {len(con)} con correo en Apollo, {len(sin)} sin correo en Apollo.")
    print(f"Gasto máximo estimado: Apollo {len(con) + len(sin)} créditos; "
          f"Hunter {len(con)} verificaciones y hasta {len(sin)} búsquedas.")
    if not args.simular and not args.si:
        if input("¿Continuar? (s/n): ").strip().lower() != "s":
            sys.exit("Cancelado.")
    if not args.simular and os.environ.get("HUNTER_API_KEY"):
        cuenta = hunter("/account", {}, False) or {}
        req = (cuenta.get("data") or {}).get("requests") or {}
        print(f"Hunter disponible: búsquedas {req.get('searches', {}).get('remaining')}, "
              f"verificaciones {req.get('verifications', {}).get('remaining')}")

    filas = []
    for p in con:
        fila = {"busqueda": p["busqueda"], "caso": "apollo_con_correo", "title": p.get("title")}
        try:
            persona, org = enriquecer(p["apollo_id"], args)
            correo = persona.get("email")
            fila.update({"apollo_email_status": persona.get("email_status"), "correo": enmascarar_correo(correo),
                         "dominio": org.get("primary_domain"), "industria": org.get("industry"),
                         "empleados": org.get("estimated_num_employees")})
            if correo and os.environ.get("HUNTER_API_KEY"):
                v = (hunter("/email-verifier", {"email": correo}, args.simular) or {}).get("data") or {}
                fila.update({"hunter_status": v.get("status"), "hunter_score": v.get("score"),
                             "webmail": v.get("webmail")})
        except ErrorApi as e:
            fila["error"] = str(e)
        filas.append(fila)
        time.sleep(args.pausa)

    for p in sin:
        fila = {"busqueda": p["busqueda"], "caso": "apollo_sin_correo", "title": p.get("title")}
        try:
            persona, org = enriquecer(p["apollo_id"], args)
            dominio = org.get("primary_domain")
            fila.update({"apollo_email_status": persona.get("email_status"), "dominio": dominio,
                         "industria": org.get("industry"), "empleados": org.get("estimated_num_employees")})
            if persona.get("email"):
                fila["correo"] = enmascarar_correo(persona["email"])
            elif dominio and persona.get("last_name") and os.environ.get("HUNTER_API_KEY"):
                h = (hunter("/email-finder", {"domain": dominio, "first_name": persona.get("first_name"),
                                              "last_name": persona.get("last_name")}, args.simular) or {}).get("data") or {}
                fila.update({"correo": enmascarar_correo(h.get("email")), "hunter_score": h.get("score"),
                             "hunter_status": (h.get("verification") or {}).get("status")})
        except ErrorApi as e:
            fila["error"] = str(e)
        filas.append(fila)
        time.sleep(args.pausa)

    if args.simular:
        print("\nSimulación terminada: no se llamó a ninguna API.")
        return
    campos = ["busqueda", "caso", "title", "apollo_email_status", "correo", "hunter_status", "hunter_score",
              "webmail", "dominio", "industria", "empleados", "error"]
    with open(RESULTADOS / "etapa_b_muestra.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=campos, extrasaction="ignore")
        w.writeheader()
        w.writerows(filas)
    filas_a = list(csv.DictReader(open(RESULTADOS / "etapa_a_busquedas.csv", encoding="utf-8")))
    escribir_resumen(filas_a, filas)
    print(f"\nListo. Resumen en {RESULTADOS / 'resumen.md'}")


# ---------------------------------------------------------------- resumen

def escribir_resumen(filas_a, filas_b):
    ok = [f for f in filas_a if not f.get("error")]
    total = sum(int(f["gerentes_encontrados"]) for f in ok)
    verificados = sum(int(f["con_correo_verificado"]) for f in ok)
    muestra = sum(int(f["muestra_revisada"]) for f in ok)
    con_correo = sum(int(f["muestra_con_correo"]) for f in ok)
    lineas = [
        "# Resultado de la prueba de cobertura (tarea 1.7)", "",
        f"- Fecha: {datetime.now():%Y-%m-%d %H:%M}",
        f"- Búsquedas: {len(ok)} correctas de {len(filas_a)}", "",
        "## Etapa A (0 créditos)", "",
        f"- Gerentes encontrados (suma de las búsquedas): **{total}**",
        f"- Con correo verificado en Apollo: **{verificados}** ({porcentaje(verificados, total)} %)",
        f"- Muestra revisada (primera página de cada búsqueda): {muestra}; con correo: {con_correo} "
        f"({porcentaje(con_correo, muestra)} %)", "",
        "| Búsqueda | Descripción | Gerentes | % con correo (muestra) | % verificado |",
        "|---|---|---:|---:|---:|",
    ]
    for f in filas_a:
        if f.get("error"):
            lineas.append(f"| {f['id']} | {f['descripcion']} | error | | |")
        else:
            lineas.append(f"| {f['id']} | {f['descripcion']} | {f['gerentes_encontrados']} | "
                          f"{f['pct_muestra_con_correo']} | {f['pct_verificado']} |")
    for campo, titulo in (("ciudad", "Por ciudad"), ("sector", "Por sector")):
        grupos = {}
        for f in ok:
            g = grupos.setdefault(f[campo], [0, 0])
            g[0] += int(f["gerentes_encontrados"])
            g[1] += int(f["con_correo_verificado"])
        lineas += ["", f"### {titulo}", "", "| | Gerentes | Verificados | % |", "|---|---:|---:|---:|"]
        for nombre, (t, v) in sorted(grupos.items(), key=lambda x: -x[1][0]):
            lineas.append(f"| {nombre} | {t} | {v} | {porcentaje(v, t)} |")
    if filas_b:
        con = [f for f in filas_b if f["caso"] == "apollo_con_correo" and not f.get("error")]
        sin = [f for f in filas_b if f["caso"] == "apollo_sin_correo" and not f.get("error")]
        con_correo_real = sum(1 for f in con if f.get("correo"))
        hunter_valido = sum(1 for f in con if f.get("hunter_status") == "valid")
        hunter_accept_all = sum(1 for f in con if f.get("hunter_status") == "accept_all")
        rescatados = sum(1 for f in sin if f.get("correo"))
        lineas += [
            "", "## Etapa B (muestra con créditos)", "",
            f"- Apollo decía que tenían correo: {len(con)}. Apollo lo entregó: {con_correo_real} "
            f"({porcentaje(con_correo_real, len(con))} %).",
            f"- Hunter los verificó como `valid`: {hunter_valido}; `accept_all` (dudosos): {hunter_accept_all}.",
            f"- Sin correo en Apollo: {len(sin)}. Hunter encontró correo para {rescatados} "
            f"({porcentaje(rescatados, len(sin))} %).",
        ]
    lineas += [
        "", "## Cómo leerlo", "",
        "- **% verificado** es lo que importa: son los gerentes a los que se les podría escribir sin rebote.",
        "- Apollo cobra 1 crédito por correo entregado. Para conseguir N correos hay que enriquecer "
        "≈ N ÷ (% con correo de la muestra) personas.",
        "- El umbral para seguir con Apollo o reevaluar el proveedor lo define el equipo (propuesta en README.md).",
    ]
    (RESULTADOS / "resumen.md").write_text("\n".join(lineas) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="Prueba de cobertura de Apollo y Hunter (tarea 1.7)")
    parser.add_argument("--etapa", choices=["a", "b"], default="a")
    parser.add_argument("--simular", action="store_true", help="Muestra las llamadas sin hacerlas")
    parser.add_argument("--solo", help="IDs de búsquedas separados por coma, por ejemplo B01,B02")
    parser.add_argument("--pausa", type=float, default=1.5, help="Segundos entre llamadas")
    parser.add_argument("--muestra-con-correo", type=int, default=0, help="Etapa B: personas con correo en Apollo")
    parser.add_argument("--muestra-sin-correo", type=int, default=0, help="Etapa B: personas sin correo en Apollo")
    parser.add_argument("--si", action="store_true", help="Etapa B: no pedir confirmación")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    cargar_env()
    if not args.simular and not os.environ.get("APOLLO_API_KEY"):
        sys.exit("Falta APOLLO_API_KEY (en el .env de la raíz o como variable de entorno).")

    if args.etapa == "a":
        busquedas = json.loads((AQUI / "busquedas.json").read_text(encoding="utf-8"))["busquedas"]
        if args.solo:
            ids = {x.strip() for x in args.solo.split(",")}
            busquedas = [b for b in busquedas if b["id"] in ids]
        etapa_a(busquedas, args)
    else:
        etapa_b(args)


if __name__ == "__main__":
    main()
