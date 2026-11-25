# Arranca UN servicio del CRM local en la terminal actual (la usan las tareas de VS Code, .vscode\tasks.json).
#   powershell -ExecutionPolicy Bypass -File tools\crm-local\servicio.ps1 -Servicio security
# Servicios: mysql, eureka, gateway, security, params, cotizaciones, prospeccion, mf, root, gemini, apollo, minifront, detener.
#   gemini    = Gemini simulado (8771): msa-prospeccion lo usa si GEMINI_API_KEY está vacía en el .env.
#   apollo    = Apollo simulado (8772): msa-prospeccion lo usa si APOLLO_API_KEY está vacía en el .env.
#   minifront = mini front de Prospección IA (4300), abre el navegador.
# La configuración (variables, carpetas, base de datos) está en comun.ps1.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("mysql", "eureka", "gateway", "security", "params", "cotizaciones", "prospeccion", "mf", "root", "gemini", "apollo", "minifront", "detener")]
    [string]$Servicio,
    [switch]$Offline
)

. "$PSScriptRoot\comun.ps1"

if ($Servicio -eq "detener") {
    Write-Host "Cerrando los servicios del CRM local..." -ForegroundColor Cyan
    Stop-ServiciosCrm
    exit 0
}
if ($Servicio -eq "mysql") {
    Write-Host "Base de los servicios del CRM: $descripcionBase" -ForegroundColor Yellow
    Start-MySql
    exit 0
}

if ($Servicio -eq "gemini") {
    Write-Host "== Iniciando Gemini simulado ($puertoGeminiSimulado)" -ForegroundColor Cyan
    if ($envRepo.GEMINI_API_KEY) {
        Write-Host "   Hay GEMINI_API_KEY en el .env: msa-prospeccion usa Gemini real y no necesita el simulador." -ForegroundColor Green
        exit 0
    }
    if (Test-Puerto $puertoGeminiSimulado) { Write-Host "   Gemini simulado en http://127.0.0.1:$puertoGeminiSimulado (ya estaba corriendo)"; exit 0 }
    Write-Host "   Sin GEMINI_API_KEY: la calificación y la redacción salen de reglas fijas, NO de Gemini." -ForegroundColor Yellow
    python "$repo/tools/mini-front/gemini_simulado.py" --puerto $puertoGeminiSimulado
    exit $LASTEXITCODE
}
if ($Servicio -eq "apollo") {
    Write-Host "== Iniciando Apollo simulado ($puertoApolloSimulado)" -ForegroundColor Cyan
    if ($envRepo.APOLLO_API_KEY) {
        Write-Host "   Hay APOLLO_API_KEY en el .env: msa-prospeccion usa Apollo real y no necesita el simulador." -ForegroundColor Green
        exit 0
    }
    if (Test-Puerto $puertoApolloSimulado) { Write-Host "   Apollo simulado en http://127.0.0.1:$puertoApolloSimulado (ya estaba corriendo)"; exit 0 }
    Write-Host "   Sin APOLLO_API_KEY: la búsqueda trae personas y empresas INVENTADAS (dominios .example)." -ForegroundColor Yellow
    python "$repo/tools/mini-front/apollo_simulado.py" --puerto $puertoApolloSimulado
    exit $LASTEXITCODE
}
if ($Servicio -eq "minifront") {
    Write-Host "== Iniciando mini front ($puertoMiniFront)" -ForegroundColor Cyan
    if (Test-Puerto $puertoMiniFront) { Write-Host "   Mini front en http://localhost:$puertoMiniFront (ya estaba corriendo)"; Start-Process "http://localhost:$puertoMiniFront"; exit 0 }
    Set-Location $repo
    python "$repo/tools/mini-front/servidor.py" --puerto $puertoMiniFront
    exit $LASTEXITCODE
}

$s = $servicios | Where-Object { $_.Clave -eq $Servicio }
# Esta línea marca el inicio para las tareas de VS Code (beginsPattern).
Write-Host "== Iniciando $($s.Nombre)" -ForegroundColor Cyan
Assert-Frontend $s
if ($Servicio -eq "root") { Initialize-CredencialesRoot }
if ($s.Variables.ContainsKey("SPRING_DATASOURCE_URL")) { Write-Host "   base: $descripcionBase" -ForegroundColor Yellow }

# Las variables solo existen en esta terminal; no se escriben en ningún archivo ni se imprimen.
foreach ($k in $s.Variables.Keys) { Set-Item -Path "env:$k" -Value ([string]$s.Variables[$k]) }
Set-Location $s.Carpeta
$comando = Get-Comando $s
Write-Host "   $comando  (en $($s.Carpeta))" -ForegroundColor DarkGray
Invoke-Expression $comando
exit $LASTEXITCODE
