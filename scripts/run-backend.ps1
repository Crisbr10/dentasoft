<#
.SYNOPSIS
  Arranca el backend en local (mvnw spring-boot:run, perfil local) con las variables de infra/.env.

.DESCRIPTION
  - Lee infra/.env y pasa sus variables solo al proceso de Maven: no las escribe en ningun fichero,
    no toca el entorno de esta sesion, del usuario ni de la maquina, y nunca muestra un valor.
  - Traduce los nombres de infra/.env a los que espera el backend, con la misma correspondencia que
    el servicio app de infra/compose.local.yml (por ejemplo, DENTASOFT_KC_BACKEND_CLIENT_SECRET pasa
    como DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET). Las variables de infra/.env se pasan tambien con
    su propio nombre.
  - Una variable ya definida en el entorno tiene prioridad sobre infra/.env, como en Compose.
  - Si falta infra/.env o una variable obligatoria, termina con error y dice cual falta.
  - Devuelve el codigo de salida de Maven.

  Antes hay que arrancar los servicios locales (ver infra/README.md).

.EXAMPLE
  .\scripts\run-backend.ps1
  .\scripts\run-backend.ps1 -ListVariables
  .\scripts\run-backend.ps1 -MavenArgs '-Dspring-boot.run.jvmArguments=-Xmx768m'
#>
[CmdletBinding()]
param(
  # Fichero de variables. Por defecto, infra/.env del repositorio.
  [string] $EnvFile = '',
  # Solo muestra que variables pasaria (nombres, nunca valores) y no arranca nada.
  [switch] $ListVariables,
  # Argumentos adicionales para mvnw spring-boot:run.
  [string[]] $MavenArgs = @()
)

# Windows PowerShell 5.1 no conoce $PSScriptRoot en los valores por defecto de param(), asi que la
# carpeta del script se calcula aqui.
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
if (-not $EnvFile) { $EnvFile = Join-Path $ScriptDir '..\infra\.env' }

# Nombre que espera el backend -> nombre en infra/.env (servicio app de infra/compose.local.yml).
$BackendNames = [ordered]@{
  'SPRING_DATASOURCE_PASSWORD'             = 'DENTASOFT_DB_APP_PASSWORD'
  'SPRING_FLYWAY_PASSWORD'                 = 'DENTASOFT_DB_OWNER_PASSWORD'
  'DENTASOFT_PLATFORM_DATASOURCE_PASSWORD' = 'DENTASOFT_DB_PLATFORM_PASSWORD'
  'SPRING_DATA_REDIS_PASSWORD'             = 'DENTASOFT_REDIS_PASSWORD'
  'DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET' = 'DENTASOFT_KC_BACKEND_CLIENT_SECRET'
}

<#
  Lee un fichero .env como Docker Compose: NOMBRE=valor por linea, lineas vacias y comentarios (#)
  ignorados, "export " opcional, comillas simples o dobles alrededor del valor, y comentario al final
  de un valor sin comillas solo si va precedido de un espacio. Los errores nunca muestran un valor.
#>
function Read-DotEnvFile([string] $Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "No existe $Path. Crealo a partir del ejemplo: Copy-Item infra\.env.example infra\.env (ver infra/README.md)."
  }
  $values = [ordered]@{}
  $number = 0
  foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path)) {
    $number++
    $text = $line.Trim()
    if ($text -eq '' -or $text.StartsWith('#')) { continue }
    if ($text -match '^export\s+') { $text = $text -replace '^export\s+', '' }
    $match = [regex]::Match($text, '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$')
    if (-not $match.Success) {
      throw "$Path, linea ${number}: no es una asignacion NOMBRE=valor."
    }
    $value = $match.Groups[2].Value.Trim()
    if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[-1] -eq $value[0]) {
      $value = $value.Substring(1, $value.Length - 2)
    } else {
      $value = ($value -replace '\s+#.*$', '').Trim()
    }
    $values[$match.Groups[1].Value] = $value
  }
  return $values
}

<#
  Las variables que recibira Maven: las de infra/.env con su nombre y, ademas, traducidas a los
  nombres del backend. Las que ya estan en $Existing (el entorno actual) no se tocan.
#>
function Get-BackendEnvironment($DotEnv, $Existing) {
  $missing = @($BackendNames.Values | Where-Object { -not $DotEnv[$_] -and -not $Existing[$_] })
  if ($missing.Count -gt 0) {
    throw "Faltan en infra/.env, o estan vacias: $($missing -join ', ')."
  }
  $result = [ordered]@{}
  foreach ($name in $DotEnv.Keys) {
    if (-not $Existing.Contains($name)) { $result[$name] = $DotEnv[$name] }
  }
  foreach ($backendName in $BackendNames.Keys) {
    $source = $BackendNames[$backendName]
    if ($Existing.Contains($backendName)) { continue }
    $value = if ($Existing[$source]) { $Existing[$source] } else { $DotEnv[$source] }
    $result[$backendName] = $value
  }
  return $result
}

<#
  Argumentos de cmd.exe para lanzar mvnw.cmd spring-boot:run. Va con su ruta completa: con
  NoDefaultCurrentDirectoryInExePath, cmd no busca programas en la carpeta actual. /s quita solo
  las comillas exteriores, asi que la ruta puede tener espacios.
#>
function Get-MavenArguments([string] $BackendDir, [string[]] $ExtraArgs) {
  $mvnw = Join-Path $BackendDir 'mvnw.cmd'
  $line = (@("`"$mvnw`"", 'spring-boot:run') + $ExtraArgs) -join ' '
  return "/d /s /c `"$line`""
}

function Get-CurrentEnvironment {
  $current = @{}
  foreach ($entry in [System.Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
    $current[[string] $entry.Key] = [string] $entry.Value
  }
  return $current
}

function Invoke-RunBackend {
  try {
    $environment = Get-BackendEnvironment (Read-DotEnvFile $EnvFile) (Get-CurrentEnvironment)
  } catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    return 1
  }

  if ($ListVariables) {
    Write-Host "Variables que recibiria Maven (sin valores):"
    foreach ($name in $environment.Keys) {
      $source = $BackendNames[$name]
      if ($source) { Write-Host "  $name  <- $source" } else { Write-Host "  $name" }
    }
    return 0
  }

  $backend = (Resolve-Path (Join-Path $ScriptDir '..\backend')).Path
  # Variables solo para el proceso hijo: ProcessStartInfo copia el entorno actual y se le anaden
  # estas, sin cambiar el de esta sesion.
  $start = New-Object System.Diagnostics.ProcessStartInfo
  $start.FileName = Join-Path $env:SystemRoot 'System32\cmd.exe'
  $start.Arguments = Get-MavenArguments $backend $MavenArgs
  $start.WorkingDirectory = $backend
  $start.UseShellExecute = $false
  foreach ($name in $environment.Keys) { $start.EnvironmentVariables[$name] = $environment[$name] }

  Write-Host "Arrancando el backend con $($environment.Count) variables de infra/.env (Ctrl+C para pararlo)..."
  $process = [System.Diagnostics.Process]::Start($start)
  $process.WaitForExit()
  return $process.ExitCode
}

# Al cargarlo con ". .\scripts\run-backend.ps1" (los tests) solo se definen las funciones.
if ($MyInvocation.InvocationName -ne '.') {
  exit (Invoke-RunBackend)
}
