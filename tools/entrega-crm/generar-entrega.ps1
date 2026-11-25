# Genera el ZIP con los cambios de Prospeccion IA en los repos del CRM (rama feature/prospeccion-ia),
# para entregarlo al programador del CRM. Por cada repo con commits propios incluye:
#   - parches\*.patch : un archivo por commit (git format-patch), se aplican con git am
#   - <repo>.bundle   : la rama completa (respaldo, se trae con git fetch)
# No sube nada a Bitbucket. Uso (desde PowerShell, en la raiz de este repo):
#   powershell -ExecutionPolicy Bypass -File tools\entrega-crm\generar-entrega.ps1

param(
    # Carpeta que contiene los repos del CRM (por defecto, la carpeta padre de este repo).
    [string]$Raiz = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
)

$ErrorActionPreference = "Stop"
$repos = @("gtc-crm-root", "mf-cotizaciones", "msa-apigateway-crm", "msa-params-crm")
$rama = "feature/prospeccion-ia"
$base = "origin/feature/deployCrm"
$fecha = Get-Date -Format "yyyy-MM-dd_HHmm"
$salida = Join-Path $Raiz "entregas-prospeccion-ia\entrega_$fecha"
$hayCambios = $false
$resumen = @()

foreach ($repo in $repos) {
    $ruta = Join-Path $Raiz $repo
    Write-Host "===== $repo"
    if (-not (Test-Path (Join-Path $ruta ".git"))) {
        Write-Warning "  No existe $ruta. Se omite."
        $resumen += "| $repo | no encontrado | |"
        continue
    }
    $actual = git -C $ruta branch --show-current
    if ($actual -ne $rama) {
        Write-Warning "  Estas en '$actual', no en $rama. Haz: git -C $ruta switch $rama"
        $resumen += "| $repo | rama equivocada ($actual) | |"
        continue
    }
    $pendientes = git -C $ruta status --porcelain
    if ($pendientes) {
        Write-Warning "  Hay cambios sin commit. Haz commit (o descartalos) y vuelve a correr el script."
        $pendientes | ForEach-Object { Write-Host "    $_" }
        $resumen += "| $repo | cambios sin commit | |"
        continue
    }
    git -C $ruta fetch -q origin
    $propios = [int](git -C $ruta rev-list --count "$base..$rama")
    $atras = [int](git -C $ruta rev-list --count "$rama..$base")
    if ($atras -gt 0) {
        Write-Warning "  El CRM tiene $atras commits nuevos que tu rama no tiene. Antes de entregar haz:"
        Write-Host "    git -C $ruta rebase $base"
    }
    if ($propios -eq 0) {
        Write-Host "  Sin commits propios: no se incluye."
        $resumen += "| $repo | sin cambios | 0 |"
        continue
    }
    $destino = Join-Path $salida $repo
    New-Item -ItemType Directory -Force -Path (Join-Path $destino "parches") | Out-Null
    git -C $ruta format-patch -q "$base..$rama" -o (Join-Path $destino "parches")
    git -C $ruta bundle create (Join-Path $destino "$repo.bundle") "$base..$rama" 2>$null
    git -C $ruta log --reverse --format="- %h %s" "$base..$rama" | Out-File -Encoding utf8 (Join-Path $destino "commits.txt")
    $estado = "listo"
    if ($atras -gt 0) { $estado = "listo, pero $atras commits atras de deployCrm" }
    $resumen += "| $repo | $estado | $propios |"
    $hayCambios = $true
    Write-Host "  $propios commits empaquetados."
}

if (-not $hayCambios) {
    Write-Host ""
    Write-Host "No hay nada que entregar (ningun repo tiene commits propios listos)."
    exit 0
}

Copy-Item (Join-Path $PSScriptRoot "INSTRUCCIONES-PARA-CRM.md") $salida
@("# Contenido de la entrega $fecha", "", "| Repo | Estado | Commits |", "|---|---|---:|") + $resumen |
    Out-File -Encoding utf8 (Join-Path $salida "CONTENIDO.md")
$zip = "$salida.zip"
Compress-Archive -Path (Join-Path $salida "*") -DestinationPath $zip -Force
Write-Host ""
Write-Host "Entrega lista: $zip"
