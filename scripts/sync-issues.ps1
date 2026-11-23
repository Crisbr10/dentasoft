<#
.SYNOPSIS
  Crea en GitHub los milestones, etiquetas e issues de las tareas definidas en docs/plan/M*.md.

.DESCRIPTION
  - Un milestone por modulo (titulo = primera linea "# ..." del fichero).
  - Una etiqueta por modulo (modulo:M04) y la etiqueta comun tipo:tarea.
  - Un issue por cada tarea "### Mxx-Tyy <punto medio> Titulo" de la seccion de tareas.
  - Idempotente: si ya existe un issue cuyo titulo empieza por el ID de la tarea, no lo crea otra vez.
  - Las tareas con "Estado: hecho" se crean cerradas.
  - Con -Update, ademas, reescribe el cuerpo de los issues que ya existen con el texto actual del plan
    (util cuando se cambia la descripcion o los criterios de una tarea). No toca titulo, estado ni etiquetas.

  Requiere GitHub CLI autenticado:  winget install GitHub.cli ;  gh auth login

.EXAMPLE
  .\scripts\sync-issues.ps1 -DryRun
  .\scripts\sync-issues.ps1
  .\scripts\sync-issues.ps1 -Modulo M04
  .\scripts\sync-issues.ps1 -Update -DryRun
#>
param(
  [string]$Modulo = "",
  [switch]$DryRun,
  [switch]$Update
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  throw "Falta GitHub CLI. Instala con 'winget install GitHub.cli' y ejecuta 'gh auth login'."
}

$root    = Split-Path -Parent $PSScriptRoot
$planDir = Join-Path $root "docs\plan"
$dot     = [char]0x00B7
$repo    = gh repo view --json nameWithOwner -q .nameWithOwner
if (-not $repo) { throw "No se pudo identificar el repositorio. Ejecuta el script dentro del repo." }

$script:labels     = @(gh label list --limit 500 --json name -q ".[].name")
$script:milestones = @(gh api "repos/$repo/milestones?state=all&per_page=100" | ConvertFrom-Json)
$existingIssues    = @(gh issue list --state all --limit 2000 --json number,title | ConvertFrom-Json)

function Ensure-Label([string]$name, [string]$color, [string]$desc) {
  if ($script:labels -contains $name) { return }
  if ($DryRun) { Write-Host "[dry-run] etiqueta $name"; return }
  gh label create $name --color $color --description $desc | Out-Null
  $script:labels += $name
  Write-Host "+ etiqueta $name"
}

function Ensure-Milestone([string]$title, [string]$desc) {
  if ($script:milestones | Where-Object { $_.title -eq $title }) { return }
  if ($DryRun) { Write-Host "[dry-run] milestone $title"; return }
  $created = gh api "repos/$repo/milestones" -f "title=$title" -f "description=$desc" | ConvertFrom-Json
  $script:milestones += $created
  Write-Host "+ milestone $title"
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
  Ensure-Milestone $moduleTitle "Especificacion: $fileUrl"

  foreach ($task in (Get-Tasks $lines)) {
    $issueTitle = "$($task.Id) $dot $($task.Title)"
    $done = $task.Body -match "\*\*Estado\*\*:\s*hecho"
    $issueBody = "$($task.Body)`n`n---`nModulo: **$moduleTitle** - especificacion en [docs/plan/$($file.Name)]($fileUrl)."
    $existing = $existingIssues | Where-Object { $_.title.StartsWith($task.Id) } | Select-Object -First 1
    if ($existing) {
      if ($Update) {
        if ($DryRun) { Write-Host "[dry-run] actualizar #$($existing.number) $($task.Id)"; continue }
        $tmpU = New-TemporaryFile
        [System.IO.File]::WriteAllText($tmpU.FullName, $issueBody, (New-Object System.Text.UTF8Encoding($false)))
        gh issue edit $existing.number --body-file $tmpU.FullName | Out-Null
        Remove-Item $tmpU.FullName
        Write-Host "~ actualizado #$($existing.number) $($task.Id)"
        $updated++
      }
      else { $skipped++ }
      continue
    }
    if ($DryRun) { Write-Host "[dry-run] issue $issueTitle$(if ($done) { ' (cerrado)' })"; continue }
    $tmp = New-TemporaryFile
    [System.IO.File]::WriteAllText($tmp.FullName, $issueBody, (New-Object System.Text.UTF8Encoding($false)))
    $url = gh issue create --title $issueTitle --body-file $tmp.FullName --label $label --label "tipo:tarea" --milestone $moduleTitle
    Remove-Item $tmp.FullName
    if ($done) { gh issue close $url --reason completed | Out-Null }
    Write-Host "+ $issueTitle"
    $created++
  }
}
Write-Host ""
Write-Host "Issues creados: $created. Actualizados: $updated. Ya existentes sin cambios: $skipped."
