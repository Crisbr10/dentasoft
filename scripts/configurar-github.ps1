<#
.SYNOPSIS
  Aplica en GitHub la configuracion de seguridad del repositorio y la proteccion de la rama main.

.DESCRIPTION
  - Permisos por defecto de GITHUB_TOKEN en solo lectura; las Actions no pueden aprobar PR.
  - Escaneo de secretos de GitHub con proteccion de push.
  - Alertas y actualizaciones de seguridad de Dependabot.
  - Ruleset "main" a partir de .github/rulesets/main.json: PR obligatorio (solo squash),
    check "ci-ok" de GitHub Actions en verde, sin alertas altas de CodeQL, sin force push
    ni borrado de la rama. Sin excepciones, tampoco para administradores.

  Idempotente: si el ruleset ya existe, lo reemplaza por el del fichero.
  Necesita que el repositorio sea publico (en el plan gratuito, GitHub no permite rulesets
  ni code scanning en repositorios privados personales).

  Requiere GitHub CLI autenticado con permisos de administracion:  gh auth login

.EXAMPLE
  .\scripts\configurar-github.ps1 -DryRun
  .\scripts\configurar-github.ps1
#>
param(
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  throw "Falta GitHub CLI. Instala con 'winget install GitHub.cli' y ejecuta 'gh auth login'."
}

$root    = Split-Path -Parent $PSScriptRoot
$ruleset = Join-Path $root ".github\rulesets\main.json"

$remote = git -C $root remote get-url origin
if ($remote -notmatch "github\.com[:/](?<repo>[^/]+/[^/]+?)(\.git)?$") {
  throw "No se pudo leer el repositorio del remoto 'origin': $remote"
}
$repo = $Matches["repo"]
Write-Host "Repositorio: $repo"

function Invoke-Gh {
  # Ejecuta gh y falla con un mensaje claro si devuelve error
  $out = & gh @args
  if ($LASTEXITCODE -ne 0) { throw "Fallo: gh $($args -join ' ')" }
  return $out
}

function Invoke-Write([string]$description, [string[]]$ghArgs) {
  # Escribe en GitHub salvo en -DryRun
  if ($DryRun) { Write-Host "[dry-run] $description"; return }
  Invoke-Gh @ghArgs | Out-Null
  Write-Host "OK  $description"
}

$visibility = Invoke-Gh api "repos/$repo" --jq ".visibility"
if ($visibility -ne "public") {
  throw "El repositorio es '$visibility'. Hazlo publico antes (Settings > General > Danger Zone)."
}

Invoke-Write "GITHUB_TOKEN en solo lectura por defecto" @(
  "api", "-X", "PUT", "repos/$repo/actions/permissions/workflow",
  "-f", "default_workflow_permissions=read", "-F", "can_approve_pull_request_reviews=false")

$security = New-TemporaryFile
try {
  $body = '{"security_and_analysis":{"secret_scanning":{"status":"enabled"},' +
          '"secret_scanning_push_protection":{"status":"enabled"}}}'
  [System.IO.File]::WriteAllText($security.FullName, $body, (New-Object System.Text.UTF8Encoding($false)))
  Invoke-Write "Escaneo de secretos con proteccion de push" @(
    "api", "-X", "PATCH", "repos/$repo", "--input", $security.FullName)
} finally {
  Remove-Item $security.FullName -ErrorAction SilentlyContinue
}

Invoke-Write "Alertas de Dependabot" @("api", "-X", "PUT", "repos/$repo/vulnerability-alerts")
Invoke-Write "Actualizaciones de seguridad de Dependabot" @(
  "api", "-X", "PUT", "repos/$repo/automated-security-fixes")

# Filtrado en PowerShell y no con --jq: Windows PowerShell 5.1 no escapa las comillas dobles
# de un argumento al llamar a un programa externo, y el filtro llegaria roto a gh.
# ForEach-Object desenrolla el array: en 5.1, ConvertFrom-Json lo emite como un solo objeto.
$existing = (Invoke-Gh api "repos/$repo/rulesets") -join "`n" | ConvertFrom-Json |
  ForEach-Object { $_ } | Where-Object { $_.name -eq "main" } |
  Select-Object -ExpandProperty id -First 1
if ($existing) {
  Invoke-Write "Ruleset main actualizado (id $existing)" @(
    "api", "-X", "PUT", "repos/$repo/rulesets/$existing", "--input", $ruleset)
} else {
  Invoke-Write "Ruleset main creado" @("api", "-X", "POST", "repos/$repo/rulesets", "--input", $ruleset)
}
