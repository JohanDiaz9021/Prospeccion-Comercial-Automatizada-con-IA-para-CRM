"""Genera un JWT de prueba para el perfil local, firmado como los del CRM (HMAC con JWT_SECRET_KEY en Base64).

Solo para desarrollo: usa la JWT_SECRET_KEY inventada del .env local, nunca la del gateway de QA o producción.
Imprime solo el token, para poder usarlo así (PowerShell):

    $token = python tools/token-local/generar_token.py
    curl.exe -H "Authorization: Bearer $token" http://localhost:8099/msa-prospeccion/api/config

Opciones: --sub, --tenant, --roles, --minutos, --alg (ver --help).
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path

RAIZ_REPO = Path(__file__).resolve().parent.parent.parent
ALGORITMOS = {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}


def leer_env():
    valores = {}
    env = RAIZ_REPO / ".env"
    if env.exists():
        for linea in env.read_text(encoding="utf-8").splitlines():
            linea = linea.strip()
            if linea and not linea.startswith("#") and "=" in linea:
                clave, valor = linea.split("=", 1)
                valores[clave.strip()] = valor.strip()
    valores.update({k: v for k, v in os.environ.items() if k in ("JWT_SECRET_KEY", "LOCAL_TENANT_CODE")})
    return valores


def b64url(datos):
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode("ascii")


def main():
    env = leer_env()
    parser = argparse.ArgumentParser(description="JWT de prueba para el perfil local")
    parser.add_argument("--sub", default="1", help="Usuario (sc_user.ID o LOGIN). Por defecto 1")
    parser.add_argument("--tenant", default=env.get("LOCAL_TENANT_CODE", "tenant_demo"),
                        help="Claim db_code. Por defecto LOCAL_TENANT_CODE del .env")
    parser.add_argument("--sin-tenant", action="store_true", help="No incluir db_code (para probar el rechazo)")
    parser.add_argument("--roles", default="ADMINISTRADOR", help="Roles separados por coma")
    parser.add_argument("--minutos", type=int, default=60, help="Minutos de vigencia (negativo = vencido)")
    parser.add_argument("--alg", choices=sorted(ALGORITMOS), default="HS512")
    args = parser.parse_args()

    try:
        print(crear_token(env, sub=args.sub, tenant=None if args.sin_tenant else args.tenant,
                          roles=args.roles, minutos=args.minutos, alg=args.alg))
    except ValueError as e:
        sys.exit(str(e))


def crear_token(env, sub="1", tenant=None, roles="ADMINISTRADOR", minutos=60, alg="HS512"):
    """Token firmado con JWT_SECRET_KEY del env. tenant=None: sin claim db_code. También lo usa el mini front."""
    secreto = env.get("JWT_SECRET_KEY")
    if not secreto:
        raise ValueError("Falta JWT_SECRET_KEY en el .env de la raíz del repo.")
    llave = base64.b64decode(secreto)

    ahora = int(time.time())
    claims = {"sub": sub, "iat": ahora, "exp": ahora + minutos * 60,
              "roles": [r.strip() for r in roles.split(",") if r.strip()]}
    if tenant is not None:
        claims["db_code"] = tenant

    cabecera = b64url(json.dumps({"alg": alg, "typ": "JWT"}, separators=(",", ":")).encode())
    cuerpo = b64url(json.dumps(claims, separators=(",", ":")).encode())
    firma = hmac.new(llave, f"{cabecera}.{cuerpo}".encode("ascii"), ALGORITMOS[alg]).digest()
    return f"{cabecera}.{cuerpo}.{b64url(firma)}"


if __name__ == "__main__":
    main()
