<#
.SYNOPSIS
  Crea en GitHub los milestones, etiquetas e issues de las tareas definidas en docs/plan/M*.md.

.DESCRIPTION
  - Un milestone por modulo (titulo = primera linea "# ..." del fichero).
  - Una etiqueta por modulo (modulo:M04) y las etiquetas comunes tipo:tarea y tipo:bug.
  - Un issue por cada tarea "### Mxx-Tyy <punto medio> Titulo" de la seccion de tareas.
  - Idempotente: si ya existe un issue cuyo titulo empieza por el ID de la tarea, no lo crea otra vez.
  - Las tareas con "Estado: hecho" se crean cerradas.
  - Con -Update, ademas, reescribe el cuerpo de los issues que ya existen con el texto actual del plan
    (util cuando se cambia la descripcion o los criterios de una tarea). No toca titulo, estado ni etiquetas.

  Usa solo la API REST de GitHub (no GraphQL) y espera entre escrituras para no superar los limites
  de GitHub (incluido el limite secundario de creacion de contenido).

  Requiere GitHub CLI autenticado:  winget install GitHub.cli ;  gh auth login

.EXAMPLE
  .\scripts\sync-issues.ps1 -DryRun
  .\scripts\sync-issues.ps1
  .\scripts\sync-issues.ps1 -Modulo M04
  .\scripts\sync-issues.ps1 -Update -Modulo M07
#>
param(
  [string]$Modulo = "",
  [switch]$DryRun,
  [switch]$Update,
  [int]$PauseSeconds = 3
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$utf8 = New-Object System.Text.UTF8Encoding($false)

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  throw "Falta GitHub CLI. Instala con 'winget install GitHub.cli' y ejecuta 'gh auth login'."
}

$root    = Split-Path -Parent $PSScriptRoot
$planDir = Join-Path $root "docs\plan"
$dot     = [char]0x00B7

# Repositorio a partir del remoto de git (sin llamar a la API)
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

function Write-JsonFile($obj) {
  $tmp = New-TemporaryFile
  [System.IO.File]::WriteAllText($tmp.FullName, ($obj | ConvertTo-Json -Depth 5), $utf8)
  return $tmp.FullName
}

function Get-Paged([string]$path, [string]$jq) {
  $lines = Invoke-Gh api $path --paginate --jq $jq
  $items = @()
  foreach ($l in $lines) { if ("$l".Trim()) { $items += ("$l" | ConvertFrom-Json) } }
  return $items
}

$labels     = @(Get-Paged "repos/$repo/labels?per_page=100" ".[] | {name}") | ForEach-Object { $_.name }
$milestones = @(Get-Paged "repos/$repo/milestones?state=all&per_page=100" ".[] | {number, title}")
$issues     = @(Get-Paged "repos/$repo/issues?state=all&per_page=100" ".[] | select(.pull_request | not) | {number, title}")
Write-Host "Etiquetas: $($labels.Count) - Milestones: $($milestones.Count) - Issues: $($issues.Count)"

function Ensure-Label([string]$name, [string]$color, [string]$desc) {
  if ($script:labels -contains $name) { return }
  if ($DryRun) { Write-Host "[dry-run] etiqueta $name"; return }
  Invoke-Gh api "repos/$repo/labels" -f "name=$name" -f "color=$color" -f "description=$desc" | Out-Null
  $script:labels += $name
  Write-Host "+ etiqueta $name"
  Start-Sleep -Seconds $PauseSeconds
}

function Ensure-Milestone([string]$title, [string]$desc) {
  $found = $script:milestones | Where-Object { $_.title -eq $title } | Select-Object -First 1
  if ($found) { return $found.number }
  if ($DryRun) { Write-Host "[dry-run] milestone $title"; return 0 }
  $created = Invoke-Gh api "repos/$repo/milestones" -f "title=$title" -f "description=$desc" | ConvertFrom-Json
  $script:milestones += [pscustomobject]@{ number = $created.number; title = $created.title }
  Write-Host "+ milestone $title"
  Start-Sleep -Seconds $PauseSeconds
  return $created.number
}

function Get-Tasks([string[]]$lines) {
  $tasks = @()
  $current = $null
  $body = New-Object System.Collections.Generic.List[string]
  $pattern = "^### (M\d{2}-T\d{2}) $dot (.+)$"
  foreach ($line in ($lines + @("## FIN"))) {
    if ($line -match $pattern) {
      if ($current) { $tasks += [pscustomobject]@{ Id = $current.Id; Title = $current.Title; Body = ($body -join "`n").Trim() } }
      $current = @{ Id = $Matches[1]; Title = $Matches[2].Trim() }
      $body = New-Object System.Collections.Generic.List[string]
    }
    elseif ($line -match "^## " -and $current) {
      $tasks += [pscustomobject]@{ Id = $current.Id; Title = $current.Title; Body = ($body -join "`n").Trim() }
      $current = $null
    }
    elseif ($current) { $body.Add($line) }
  }
  return $tasks
}

Ensure-Label "tipo:tarea" "0E8A16" "Tarea del plan del MVP"
Ensure-Label "tipo:bug"   "D73A4A" "Error"

$files = Get-ChildItem $planDir -Filter "M*.md" | Sort-Object Name
if ($Modulo) { $files = $files | Where-Object { $_.Name -like "$Modulo-*" } }
if (-not $files) { throw "No hay ficheros de modulo que coincidan." }

$created = 0; $skipped = 0; $updated = 0
foreach ($file in $files) {
  $lines = Get-Content $file.FullName -Encoding UTF8
  $moduleTitle = (($lines | Where-Object { $_ -match "^# " } | Select-Object -First 1) -replace "^# ", "").Trim()
  $code = $file.BaseName.Split("-")[0]
  $label = "modulo:$code"
  $fileUrl = "https://github.com/$repo/blob/main/docs/plan/$($file.Name)"

  Ensure-Label $label "1D76DB" $moduleTitle
  $milestoneNumber = Ensure-Milestone $moduleTitle "Especificacion: $fileUrl"

  foreach ($task in (Get-Tasks $lines)) {
    $issueTitle = "$($task.Id) $dot $($task.Title)"
    $done = $task.Body -match "\*\*Estado\*\*:\s*hecho"
    $issueBody = "$($task.Body)`n`n---`nModulo: **$moduleTitle** - especificacion en [docs/plan/$($file.Name)]($fileUrl)."
    $existing = $issues | Where-Object { $_.title.StartsWith($task.Id) } | Select-Object -First 1

    if ($existing) {
      if (-not $Update) { $skipped++; continue }
      if ($DryRun) { Write-Host "[dry-run] actualizar #$($existing.number) $($task.Id)"; continue }
      $json = Write-JsonFile @{ body = $issueBody }
      Invoke-Gh api -X PATCH "repos/$repo/issues/$($existing.number)" --input $json | Out-Null
      Remove-Item $json
      Write-Host "~ actualizado #$($existing.number) $($task.Id)"
      $updated++
      Start-Sleep -Seconds $PauseSeconds
      continue
    }

    if ($DryRun) { Write-Host "[dry-run] issue $issueTitle$(if ($done) { ' (cerrado)' })"; continue }
    $payload = @{ title = $issueTitle; body = $issueBody; labels = @($label, "tipo:tarea"); milestone = $milestoneNumber }
    $json = Write-JsonFile $payload
    $new = Invoke-Gh api "repos/$repo/issues" --input $json | ConvertFrom-Json
    Remove-Item $json
    if ($done) {
      Invoke-Gh api -X PATCH "repos/$repo/issues/$($new.number)" -f "state=closed" -f "state_reason=completed" | Out-Null
    }
    $issues += [pscustomobject]@{ number = $new.number; title = $issueTitle }
    Write-Host "+ #$($new.number) $issueTitle"
    $created++
    Start-Sleep -Seconds $PauseSeconds
  }
}
Write-Host ""
Write-Host "Issues creados: $created. Actualizados: $updated. Ya existentes sin cambios: $skipped."
