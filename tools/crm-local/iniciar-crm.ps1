# Arranca el CRM completo en local junto con msa-prospeccion, UNA VENTANA DE POWERSHELL POR SERVICIO:
#   MySQL -> Eureka -> gateway -> msa-security -> msa-params -> msa-cotizaciones -> msa-prospeccion
#   -> mf-cotizaciones -> gtc-crm-root
# Alternativa dentro de VS Code (una terminal por servicio en el panel): Terminal > Run Task > "CRM completo".
#
# Uso, desde la raíz de este repo:
#   powershell -ExecutionPolicy Bypass -File tools\crm-local\iniciar-crm.ps1
# Opciones:
#   -CrearTablas  Solo la primera vez y solo con la base local: msa-security, msa-params y msa-cotizaciones crean
#                 sus tablas (Hibernate update). Los servicios que ya estén corriendo no se reinician.
#   -SinFront     Solo los backends.
#   -Offline      Gradle sin internet (usa lo ya descargado).
#   -Reiniciar    Cierra primero todo lo del CRM local y arranca desde cero.
#
# La configuración (variables, carpetas, base local o de desarrollo) está en comun.ps1. Solo desarrollo.
param(
    [switch]$CrearTablas,
    [switch]$SinFront,
    [switch]$Offline,
    [switch]$Reiniciar
)

. "$PSScriptRoot\comun.ps1"
Set-Location $repo
Write-Host "Base de los servicios del CRM: $descripcionBase" -ForegroundColor Yellow

function Test-Url([string]$url) {
    try { return (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3).StatusCode -lt 500 } catch { return $false }
}

# Abre el servicio en otra ventana con sus variables (las hereda; no quedan en la línea de comandos).
function Start-Servicio($servicio) {
    Write-Host "-> $($servicio.Nombre)" -ForegroundColor Cyan
    if (Test-Url $servicio.Salud) { Write-Host "   ya estaba corriendo" -ForegroundColor DarkGray; return }
    if (-not (Test-Path $servicio.Carpeta)) { throw "No existe la carpeta $($servicio.Carpeta)" }
    $anteriores = @{}
    foreach ($k in $servicio.Variables.Keys) {
        $anteriores[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, [string]$servicio.Variables[$k])
    }
    try {
        $titulo = "`$host.UI.RawUI.WindowTitle = '$($servicio.Nombre)'"
        Start-Process powershell -WorkingDirectory $servicio.Carpeta -ArgumentList "-NoExit", "-Command", "$titulo; $(Get-Comando $servicio)" | Out-Null
    } finally {
        foreach ($k in $servicio.Variables.Keys) { [Environment]::SetEnvironmentVariable($k, $anteriores[$k]) }
    }
    $limite = (Get-Date).AddMinutes(5)
    while (-not (Test-Url $servicio.Salud)) {
        if ((Get-Date) -gt $limite) { throw "$($servicio.Nombre) no respondió en 5 minutos. Revise su ventana." }
        Start-Sleep -Seconds 3
    }
    Write-Host "   arriba" -ForegroundColor Green
}

if ($Reiniciar) {
    Write-Host "-> Cerrando los servicios anteriores" -ForegroundColor Cyan
    Stop-ServiciosCrm
}

Write-Host "-> MySQL (Docker)" -ForegroundColor Cyan
Start-MySql

foreach ($s in $servicios | Where-Object { $_.Tipo -eq "gradle" }) { Start-Servicio $s }

# El gateway encuentra a msa-prospeccion por Eureka. Se pregunta a Eureka y no al gateway: el gateway pide token
# también en /msa-prospeccion/actuator/health y respondería 401 aunque la ruta funcione.
Write-Host "-> msa-prospeccion registrado en Eureka" -ForegroundColor Cyan
$limite = (Get-Date).AddMinutes(2)
while ($true) {
    try {
        $registro = Invoke-RestMethod -Uri "http://localhost:8761/eureka/apps/MSA-PROSPECCION-CRM" -Headers @{ Accept = "application/json" } -TimeoutSec 3
        if (@($registro.application.instance | Where-Object { $_.status -eq "UP" }).Count -gt 0) { Write-Host "   registrado" -ForegroundColor Green; break }
    } catch { }
    if ((Get-Date) -gt $limite) { Write-Warning "Eureka todavía no ve a msa-prospeccion. Revise http://localhost:8761"; break }
    Start-Sleep -Seconds 5
}

if ($SinFront) { Write-Host "Backends listos." -ForegroundColor Green; return }

Initialize-CredencialesRoot
foreach ($s in $servicios | Where-Object { $_.Tipo -eq "npm" }) { Assert-Frontend $s }
foreach ($s in $servicios | Where-Object { $_.Tipo -eq "npm" }) { Start-Servicio $s }

Write-Host "CRM listo: http://localhost:4200" -ForegroundColor Green
Start-Process "http://localhost:4200"
