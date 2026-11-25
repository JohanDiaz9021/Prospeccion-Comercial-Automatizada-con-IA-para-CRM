# Arranca todo lo del mini front: MySQL (Docker), el servicio con el perfil local y el mini front.
# Uso, desde la raíz del repo:
#   powershell -ExecutionPolicy Bypass -File tools\mini-front\iniciar.ps1
# El servicio queda en otra ventana (ciérrela para apagarlo). Ctrl+C en esta ventana apaga el mini front.
param(
    [int]$Puerto = 4300
)

$ErrorActionPreference = "Stop"
$raiz = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $raiz

if (-not (Test-Path ".env")) {
    throw "Falta el archivo .env en la raíz. Cópielo de .env.example y complete JWT_SECRET_KEY (ver README)."
}
$lineaPuerto = Get-Content ".env" | Where-Object { $_ -match '^\s*SERVER_PORT\s*=' } | Select-Object -First 1
$puertoServicio = if ($lineaPuerto) { ($lineaPuerto -split '=', 2)[1].Trim() } else { "8099" }
$salud = "http://localhost:$puertoServicio/msa-prospeccion/actuator/health"

function Test-Servicio {
    try {
        return (Invoke-WebRequest -Uri $salud -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200
    } catch {
        return $false
    }
}

# 1. MySQL local
Write-Host "1/3 MySQL local (Docker)..." -ForegroundColor Cyan
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop no está abierto. Ábralo, espere a que diga 'Engine running' y vuelva a ejecutar."
}
docker compose -f docker-compose.local.yml up -d --wait
if ($LASTEXITCODE -ne 0) { throw "No arrancó MySQL (docker compose)." }

# 2. Servicio (perfil local) en otra ventana
Write-Host "2/3 Servicio msa-prospeccion en el puerto $puertoServicio..." -ForegroundColor Cyan
if (Test-Servicio) {
    Write-Host "    Ya estaba corriendo." -ForegroundColor DarkGray
} else {
    $comando = "Set-Location '$raiz'; `$env:SPRING_PROFILES_ACTIVE = 'local'; .\gradlew.bat bootRun"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $comando | Out-Null
    $limite = (Get-Date).AddMinutes(3)
    while (-not (Test-Servicio)) {
        if ((Get-Date) -gt $limite) {
            throw "El servicio no respondió en 3 minutos. Revise la otra ventana para ver el error."
        }
        Start-Sleep -Seconds 3
        Write-Host "    esperando..." -ForegroundColor DarkGray
    }
    Write-Host "    Arriba." -ForegroundColor Green
}

# 3. Mini front (abre el navegador)
Write-Host "3/3 Mini front en http://localhost:$Puerto" -ForegroundColor Cyan
python tools\mini-front\servidor.py --puerto $Puerto
