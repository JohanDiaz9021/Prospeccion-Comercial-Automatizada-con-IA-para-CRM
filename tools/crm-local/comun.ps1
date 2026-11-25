# Configuración compartida del CRM local: la usan iniciar-crm.ps1 (ventanas) y servicio.ps1 (terminales de VS Code).
# Se carga con: . "$PSScriptRoot\comun.ps1"   (antes, el que la carga puede definir $CrearTablas y $Offline).
#
# Base de datos: si el .env trae SPRING_DATASOURCE_URL (base de desarrollo del CRM), security, params y
# cotizaciones la usan y se ven los datos reales; si no, usan la MySQL local de Docker. msa-prospeccion siempre usa
# la local: sus tablas la_* no existen en la de desarrollo. Solo desarrollo.

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$crm = Split-Path $repo -Parent
$crearTablasActivo = [bool](Get-Variable CrearTablas -ValueOnly -ErrorAction SilentlyContinue)
$offlineActivo = [bool](Get-Variable Offline -ValueOnly -ErrorAction SilentlyContinue)

# ---------- Variables del .env ----------
if (-not (Test-Path "$repo\.env")) { throw "Falta el .env en la raíz del repo (copie .env.example)." }
$envRepo = @{}
foreach ($linea in Get-Content "$repo\.env") {
    if ($linea -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { $envRepo[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($clave in "JWT_SECRET_KEY", "MYSQL_HOST_PORT", "MYSQL_DATABASE", "MYSQL_USER", "MYSQL_PASSWORD") {
    if (-not $envRepo[$clave]) { throw "Falta $clave en el .env." }
}

# Valor del .env o, si no está, uno de prueba.
function Get-Env([string]$clave, [string]$porDefecto) { if ($envRepo[$clave]) { $envRepo[$clave] } else { $porDefecto } }

$eureka = "http://localhost:8761/eureka/"
$jdbcLocal = "jdbc:mysql://localhost:$($envRepo.MYSQL_HOST_PORT)/$($envRepo.MYSQL_DATABASE)?serverTimezone=UTC"
$baseRemota = [bool]$envRepo.SPRING_DATASOURCE_URL -and ($envRepo.SPRING_DATASOURCE_URL -notmatch "//(localhost|127\.0\.0\.1)[:/]")
if ($baseRemota -and $crearTablasActivo) {
    throw "-CrearTablas no se permite con la base de desarrollo compartida: cambiaría su estructura para todo el equipo."
}
# Con la base de desarrollo, cada servicio puede tener su propio usuario (DB_USER_SECURITY / DB_PASS_SECURITY,
# DB_USER_PARAMS, DB_USER_QUOTE), igual que en sus application.properties; si no está, se usa
# SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD.
function Get-BaseDatos([string]$sufijo) {
    if (-not $baseRemota) { return $baseDatos.Clone() }
    $usuario = Get-Env "DB_USER_$sufijo" $envRepo.SPRING_DATASOURCE_USERNAME
    $clave = Get-Env "DB_PASS_$sufijo" $envRepo.SPRING_DATASOURCE_PASSWORD
    if (-not $usuario -or -not $clave) {
        throw "Falta el usuario de la base de desarrollo: DB_USER_$sufijo y DB_PASS_$sufijo (o SPRING_DATASOURCE_USERNAME y SPRING_DATASOURCE_PASSWORD) en el .env."
    }
    $datos = $baseDatos.Clone()
    $datos.SPRING_DATASOURCE_USERNAME = $usuario
    $datos.SPRING_DATASOURCE_PASSWORD = $clave
    $datos
}

$baseDatos = if ($baseRemota) {
    @{
        SPRING_DATASOURCE_URL         = $envRepo.SPRING_DATASOURCE_URL
        SPRING_JPA_HIBERNATE_DDL_AUTO = "none"
    }
} else {
    @{
        SPRING_DATASOURCE_URL         = $jdbcLocal
        SPRING_DATASOURCE_USERNAME    = $envRepo.MYSQL_USER
        SPRING_DATASOURCE_PASSWORD    = $envRepo.MYSQL_PASSWORD
        SPRING_JPA_HIBERNATE_DDL_AUTO = $(if ($crearTablasActivo) { "update" } else { "none" })
    }
}
$hostBase = ([regex]::Match($baseDatos.SPRING_DATASOURCE_URL, "//([^/?]+)")).Groups[1].Value
$descripcionBase = if ($baseRemota) { "DESARROLLO ($hostBase)" } else { "local de Docker ($hostBase)" }

# ---------- Servicios, en el orden en que arrancan ----------
# Orden del equipo: discovery primero (ahí se registran todos), luego el gateway y después el resto.
$puertoGeminiSimulado = 8771
$puertoApolloSimulado = 8772
$puertoMiniFront = 4300

function Test-Puerto([int]$puerto) {
    [bool](Get-NetTCPConnection -State Listen -LocalPort $puerto -ErrorAction SilentlyContinue)
}

function New-Servicio($clave, $nombre, $carpeta, $tipo, $salud, [hashtable]$variables) {
    [pscustomobject]@{ Clave = $clave; Nombre = $nombre; Carpeta = $carpeta; Tipo = $tipo; Salud = $salud; Variables = $variables }
}
$servicios = @(
    (New-Servicio "eureka" "Eureka (8761)" "$crm\msa-discovery-service-crm" "gradle" "http://localhost:8761/actuator/health" @{})

    (New-Servicio "gateway" "Gateway (8090)" "$crm\msa-apigateway-crm" "gradle" "http://localhost:8090/actuator/health" @{
        EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE = $eureka; EUREKA_INSTANCE_HOSTNAME_APIGATEWAY = "localhost"
        JWT_SECRET_KEY = $envRepo.JWT_SECRET_KEY
        AUTH_CORS_ALLOWED_ORIGINS = "http://localhost:4200,http://localhost:4201"
        GATEWAY_PERMISSION_MODE = "disabled"; GATEWAY_TENANT_MODE = "disabled"
    })

    (New-Servicio "security" "msa-security (8089)" "$crm\msa-security-crm" "gradle" "http://localhost:8089/msa-security/actuator/health" ((Get-BaseDatos "SECURITY") + @{
        SECURITY_AUTH_MODE = "local"; SECURITY_TENANT_MODE = "fixed"
        EUREKA_CLIENT_ENABLED = "true"; EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE = $eureka
        EUREKA_INSTANCE_HOSTNAME_SECURITY = "localhost"; JWT_SECRET_KEY = $envRepo.JWT_SECRET_KEY
        JWT_TIME_EXPIRATION = (Get-Env "JWT_TIME_EXPIRATION" "86400000")
        # En local no hay servidor de correo: sin esto el health queda DOWN aunque el login funcione.
        MANAGEMENT_HEALTH_MAIL_ENABLED = "false"
    }))

    (New-Servicio "params" "msa-params (8088)" "$crm\msa-params-crm" "gradle" "http://localhost:8088/msa-params/actuator/health" ((Get-BaseDatos "PARAMS") + @{
        PARAMS_TENANT_MODE = "fixed"; EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE = $eureka
        EUREKA_INSTANCE_HOSTNAME_PARAMS = "localhost"
    }))

    # Cotizaciones, prospectos (pm_leaflet) y tareas. FTP y datos de empresa salen del .env si están.
    # El CORREO es siempre de prueba: con el SMTP real, una acción en local podría mandar correos a clientes reales.
    # Jobs programados apagados (también envían correos).
    (New-Servicio "cotizaciones" "msa-cotizaciones (8085)" "$crm\msa-cotizaciones" "gradle" "http://localhost:8085/msa-cotizaciones/actuator/health" ((Get-BaseDatos "QUOTE") + @{
        QUOTES_TENANT_MODE = "fixed"; QUOTES_SCHEDULING_ENABLED = "false"
        EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE = $eureka; EUREKA_INSTANCE_HOSTNAME_QUOTE = "localhost"
        EMAIL_HOST = "localhost"; EMAIL_PORT = "25"; EMAIL_SENDER = "no-reply@localhost.invalid"; EMAIL_PASSWORD = "local"
        FTP_SERVER = (Get-Env "FTP_SERVER" "localhost"); FTP_USERNAME = (Get-Env "FTP_USERNAME" "local")
        FTP_PASSWORD = (Get-Env "FTP_PASSWORD" "local")
        COMPANY_NAME = (Get-Env "COMPANY_NAME" "GTC (local)"); COMPANY_EMAIL = (Get-Env "COMPANY_EMAIL" "no-reply@localhost.invalid")
        COMPANY_PHONE = (Get-Env "COMPANY_PHONE" "0000000"); COMPANY_WEBSITE = (Get-Env "COMPANY_WEBSITE" "http://localhost")
        COMPANY_PRIVACY_POLICY_LINK = (Get-Env "COMPANY_PRIVACY_POLICY_LINK" "http://localhost")
        COMPANY_TERMS_LINK = (Get-Env "COMPANY_TERMS_LINK" "http://localhost")
        MANAGEMENT_HEALTH_MAIL_ENABLED = "false"
    }))

    (New-Servicio "prospeccion" "msa-prospeccion (8099)" $repo "gradle" "http://localhost:$(Get-Env 'SERVER_PORT' '8099')/msa-prospeccion/actuator/health" ($(
        # Eureka solo si está corriendo: así el servicio también arranca solo, para el mini front.
        $vars = @{ SPRING_PROFILES_ACTIVE = "local"; EUREKA_URL = $eureka
                   EUREKA_CLIENT_ENABLED = $(if (Test-Puerto 8761) { "true" } else { "false" }) }
        # Sin GEMINI_API_KEY se usa el Gemini simulado (tools\mini-front\gemini_simulado.py, puerto 8771).
        if (-not $envRepo.GEMINI_API_KEY) {
            $vars.GEMINI_BASE_URL = "http://127.0.0.1:$puertoGeminiSimulado/v1beta"
            $vars.GEMINI_API_KEY = "simulado-local"
            $vars.GEMINI_MAX_RETRIES = "0"
        }
        # Sin APOLLO_API_KEY se usa el Apollo simulado (tools\mini-front\apollo_simulado.py, puerto 8772):
        # personas y empresas inventadas para probar la búsqueda (3.2) mientras no hay plan pago de Apollo.
        if (-not $envRepo.APOLLO_API_KEY) {
            $vars.APOLLO_BASE_URL = "http://127.0.0.1:$puertoApolloSimulado/api/v1"
            $vars.APOLLO_API_KEY = "simulado-local"
            $vars.APOLLO_MAX_RETRIES = "0"
        }
        $vars
    )))

    (New-Servicio "mf" "mf-cotizaciones (4201)" "$crm\mf-cotizaciones" "npm" "http://localhost:4201/remoteEntry.js" @{})
    (New-Servicio "root" "gtc-crm-root (4200)" "$crm\gtc-crm-root" "npm" "http://localhost:4200" @{})
)
# 8771 = Gemini simulado y 8772 = Apollo simulado. El mini front (4300) no se cierra con "detener todo": se cierra su terminal.
$puertosCrm = 8761, 8090, 8089, 8088, 8085, 8099, 4201, 4200, $puertoGeminiSimulado, $puertoApolloSimulado

function Get-Comando($servicio) {
    if ($servicio.Tipo -eq "gradle") {
        if ($offlineActivo) { ".\gradlew.bat bootRun --offline" } else { ".\gradlew.bat bootRun" }
    } else {
        "npm.cmd run start:local"
    }
}

function Assert-Frontend($servicio) {
    if ($servicio.Tipo -eq "npm" -and -not (Test-Path "$($servicio.Carpeta)\node_modules\.bin\ng.cmd")) {
        throw "Falta instalar $($servicio.Carpeta). Con internet: cd `"$($servicio.Carpeta)`"; npm.cmd ci --legacy-peer-deps"
    }
}

# Credenciales del login automático del root (.local está ignorada por git).
function Initialize-CredencialesRoot {
    $credenciales = "$crm\gtc-crm-root\.local\local-auth.json"
    if (-not (Test-Path $credenciales)) {
        New-Item -ItemType Directory -Force (Split-Path $credenciales) | Out-Null
        # Usuario creado por tools\crm-local\usuario-local.sql (solo existe en la base local).
        '{ "login": "admin.local", "password": "Local2026*", "language": "1-Espanol", "company": "GTC" }' |
            Set-Content -Encoding utf8 $credenciales
    }
    if ($baseRemota -and ((Get-Content $credenciales -Raw) -match '"admin\.local"')) {
        Write-Warning ("Con la base de desarrollo, admin.local no existe. Ponga SU usuario y clave de desarrollo en " +
            "$credenciales (login y password) y recargue http://localhost:4200.")
    }
}

function Start-MySql {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop no está abierto." }
    docker compose -f "$repo\docker-compose.local.yml" up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "No arrancó MySQL." }
}

# Cierra todo lo del CRM local: las ventanas de iniciar-crm.ps1 y cualquier proceso en sus puertos
# (incluidas las terminales de VS Code). No toca MySQL ni el mini front (4300).
function Stop-ServiciosCrm {
    $titulos = "(Eureka|Gateway|msa-security|msa-params|msa-cotizaciones|msa-prospeccion|mf-cotizaciones|gtc-crm-root) \(\d+\)"
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match "WindowTitle = '$titulos'" } |
        ForEach-Object { cmd /c "taskkill /PID $($_.ProcessId) /T /F" 2>$null | Out-Null }
    Start-Sleep -Seconds 2
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $puertosCrm -contains $_.LocalPort } |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { cmd /c "taskkill /PID $_ /T /F" 2>$null | Out-Null }
    Start-Sleep -Seconds 2
    $ocupados = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $puertosCrm -contains $_.LocalPort } | Select-Object -ExpandProperty LocalPort -Unique
    if ($ocupados) { Write-Warning "Siguen ocupados: $($ocupados -join ', ')" } else { Write-Host "   puertos libres" -ForegroundColor Green }
}
