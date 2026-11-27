# Tests de scripts/run-backend.ps1 (M00-T10), con el Pester que trae Windows (3.4):
#   Invoke-Pester .\scripts\run-backend.Tests.ps1
# Usan ficheros .env de prueba en una carpeta temporal y valores inventados; nunca leen infra/.env.

$script = Join-Path $PSScriptRoot 'run-backend.ps1'
. $script

$secretValues = @('app-pw-123', 'owner-pw-456', 'platform-pw-789', 'redis-pw-000', 'kc-secret-abc', 's3-secret-xyz')

function New-TestEnvFile([string[]] $Lines) {
  $dir = Join-Path ([System.IO.Path]::GetTempPath()) ('run-backend-tests-' + [guid]::NewGuid())
  New-Item -ItemType Directory -Path $dir | Out-Null
  $file = Join-Path $dir '.env'
  [System.IO.File]::WriteAllLines($file, $Lines)
  return $file
}

function Get-CompleteEnvLines {
  return @(
    '# Comentario',
    '',
    'DENTASOFT_DB_APP_PASSWORD=app-pw-123',
    'DENTASOFT_DB_OWNER_PASSWORD="owner-pw-456"',
    "DENTASOFT_DB_PLATFORM_PASSWORD='platform-pw-789'",
    'DENTASOFT_REDIS_PASSWORD=redis-pw-000 # comentario al final',
    'export DENTASOFT_KC_BACKEND_CLIENT_SECRET=kc-secret-abc',
    'DENTASOFT_S3_REGION=us-east-1',
    'DENTASOFT_S3_BUCKET=dentasoft-docs',
    'DENTASOFT_S3_ACCESS_KEY=s3-access',
    'DENTASOFT_S3_SECRET_KEY=s3-secret-xyz',
    'POSTGRES_IMAGE=pgvector/pgvector:pg18',
    'KC_BOOTSTRAP_ADMIN_PASSWORD=kc-admin-pw',
    'DENTASOFT_URLS_APP=https://app.dentasoft.test'
  )
}

Describe 'Read-DotEnvFile' {
  It 'lee las asignaciones, sin comentarios, comillas ni export' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $values = Read-DotEnvFile $file
      $values['DENTASOFT_DB_APP_PASSWORD'] | Should Be 'app-pw-123'
      $values['DENTASOFT_DB_OWNER_PASSWORD'] | Should Be 'owner-pw-456'
      $values['DENTASOFT_DB_PLATFORM_PASSWORD'] | Should Be 'platform-pw-789'
      $values['DENTASOFT_REDIS_PASSWORD'] | Should Be 'redis-pw-000'
      $values['DENTASOFT_KC_BACKEND_CLIENT_SECRET'] | Should Be 'kc-secret-abc'
      $values.Count | Should Be 12
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'quita las comillas aunque detras haya un comentario, como Compose' {
    $file = New-TestEnvFile @('A="con-comillas" # comentario', 'B="pegado"#comentario', "C='simple'   # otro")
    try {
      $values = Read-DotEnvFile $file
      $values['A'] | Should Be 'con-comillas'
      $values['B'] | Should Be 'pegado'
      $values['C'] | Should Be 'simple'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'falla sin mostrar el valor si unas comillas no se cierran o llevan algo detras' {
    $file = New-TestEnvFile @('A="sin-cerrar-pw', 'B="valor-pw" basura')
    try {
      $message = ''
      try { Read-DotEnvFile $file | Out-Null } catch { $message = $_.Exception.Message }
      $message | Should Match 'linea 1'
      $message | Should Not Match 'sin-cerrar-pw'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'conserva una almohadilla dentro de un valor entre comillas o sin espacio delante' {
    $file = New-TestEnvFile @('A="con # dentro"', 'B=sin#espacio')
    try {
      $values = Read-DotEnvFile $file
      $values['A'] | Should Be 'con # dentro'
      $values['B'] | Should Be 'sin#espacio'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'falla sin mostrar el contenido si una linea no es una asignacion' {
    $file = New-TestEnvFile @('DENTASOFT_REDIS_PASSWORD=redis-pw-000', 'esto-no-es-valido redis-pw-000')
    try {
      $message = ''
      try { Read-DotEnvFile $file | Out-Null } catch { $message = $_.Exception.Message }
      $message | Should Match 'linea 2'
      $message | Should Not Match 'redis-pw-000'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'falla si el fichero no existe y dice como crearlo' {
    { Read-DotEnvFile 'C:\no-existe\.env' } | Should Throw 'Copy-Item'
  }
}

Describe 'Get-BackendEnvironment' {
  It 'traduce las variables de infra/.env a los nombres del backend, como el servicio app de Compose' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $result = Get-BackendEnvironment (Read-DotEnvFile $file) @{}
      $result['DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET'] | Should Be 'kc-secret-abc'
      $result['SPRING_DATASOURCE_PASSWORD'] | Should Be 'app-pw-123'
      $result['SPRING_FLYWAY_PASSWORD'] | Should Be 'owner-pw-456'
      $result['DENTASOFT_PLATFORM_DATASOURCE_PASSWORD'] | Should Be 'platform-pw-789'
      $result['SPRING_DATA_REDIS_PASSWORD'] | Should Be 'redis-pw-000'
      $result['DENTASOFT_S3_SECRET_KEY'] | Should Be 's3-secret-xyz'
      $result['DENTASOFT_S3_BUCKET'] | Should Be 'dentasoft-docs'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'pasa solo lo que el servicio app recibe de infra/.env, ni otros secretos ni otras URLs' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $result = Get-BackendEnvironment (Read-DotEnvFile $file) @{}
      $result.Count | Should Be 9
      $result.Contains('KC_BOOTSTRAP_ADMIN_PASSWORD') | Should Be $false
      $result.Contains('POSTGRES_IMAGE') | Should Be $false
      $result.Contains('DENTASOFT_URLS_APP') | Should Be $false
      $result.Contains('DENTASOFT_KC_BACKEND_CLIENT_SECRET') | Should Be $false
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'respeta una variable ya definida en el entorno, como hace Compose' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $existing = @{ 'SPRING_DATASOURCE_PASSWORD' = 'ya-definida'; 'DENTASOFT_REDIS_PASSWORD' = 'tambien' }
      $result = Get-BackendEnvironment (Read-DotEnvFile $file) $existing
      # La del backend ya esta en el entorno: el proceso hijo la hereda tal cual.
      $result.Contains('SPRING_DATASOURCE_PASSWORD') | Should Be $false
      # La de infra/.env esta en el entorno: se traduce con el valor del entorno.
      $result['SPRING_DATA_REDIS_PASSWORD'] | Should Be 'tambien'
      $result['SPRING_FLYWAY_PASSWORD'] | Should Be 'owner-pw-456'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'dice que variables obligatorias faltan, sin ningun valor' {
    $file = New-TestEnvFile @('DENTASOFT_DB_APP_PASSWORD=app-pw-123', 'DENTASOFT_REDIS_PASSWORD=')
    try {
      $message = ''
      try { Get-BackendEnvironment (Read-DotEnvFile $file) @{} | Out-Null } catch { $message = $_.Exception.Message }
      $message | Should Match 'DENTASOFT_DB_OWNER_PASSWORD'
      $message | Should Match 'DENTASOFT_DB_PLATFORM_PASSWORD'
      $message | Should Match 'DENTASOFT_REDIS_PASSWORD'
      $message | Should Match 'DENTASOFT_KC_BACKEND_CLIENT_SECRET'
      $message | Should Match 'DENTASOFT_S3_SECRET_KEY'
      $message | Should Not Match 'DENTASOFT_DB_APP_PASSWORD'
      $message | Should Not Match 'app-pw-123'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }
}

Describe 'Get-MavenArguments' {
  It 'llama a mvnw.cmd por su ruta completa, aunque cmd no busque en la carpeta actual' {
    # Con NoDefaultCurrentDirectoryInExePath, cmd no encuentra un "mvnw.cmd" sin ruta.
    $arguments = Get-MavenArguments 'C:\Ruta con espacios\backend' @('-Dx=1')
    $arguments | Should Be '/d /s /c ""C:\Ruta con espacios\backend\mvnw.cmd" spring-boot:run -Dx=1"'
  }
}

Describe 'run-backend.ps1 -ListVariables' {
  It 'muestra los nombres y la traduccion, nunca un valor, y no arranca nada' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -EnvFile $file -ListVariables 2>&1 | Out-String
      $LASTEXITCODE | Should Be 0
      $output | Should Match 'DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET'
      $output | Should Match 'DENTASOFT_KC_BACKEND_CLIENT_SECRET'
      foreach ($value in $secretValues) { $output | Should Not Match ([regex]::Escape($value)) }
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'usa infra/.env junto a la carpeta del script cuando no se indica -EnvFile' {
    # Copia del script en un repositorio falso, para no leer nunca el infra/.env real.
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('run-backend-repo-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path (Join-Path $root 'scripts'), (Join-Path $root 'infra') | Out-Null
    Copy-Item $script (Join-Path $root 'scripts\run-backend.ps1')
    [System.IO.File]::WriteAllLines((Join-Path $root 'infra\.env'), (Get-CompleteEnvLines))
    try {
      $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\run-backend.ps1') -ListVariables 2>&1 | Out-String
      $LASTEXITCODE | Should Be 0
      $output | Should Match 'DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET'
    } finally { Remove-Item -Recurse -Force $root }
  }

  It 'termina con error y sin valores si falta una variable obligatoria' {
    $file = New-TestEnvFile @('DENTASOFT_DB_APP_PASSWORD=app-pw-123')
    try {
      $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -EnvFile $file -ListVariables 2>&1 | Out-String
      $LASTEXITCODE | Should Be 1
      $output | Should Match 'DENTASOFT_KC_BACKEND_CLIENT_SECRET'
      $output | Should Not Match 'app-pw-123'
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }

  It 'lanza mvnw.cmd del backend con las variables, devuelve su codigo de salida y no muestra valores' {
    # Repositorio falso con un mvnw.cmd que solo dice que variables recibe (nunca sus valores).
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('run-backend-launch-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path (Join-Path $root 'scripts'), (Join-Path $root 'infra'), (Join-Path $root 'backend') | Out-Null
    Copy-Item $script (Join-Path $root 'scripts\run-backend.ps1')
    [System.IO.File]::WriteAllLines((Join-Path $root 'infra\.env'), (Get-CompleteEnvLines))
    [System.IO.File]::WriteAllLines((Join-Path $root 'backend\mvnw.cmd'), @(
      '@echo off',
      'echo args=%*',
      'if defined DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET echo secret-received',
      'if defined SPRING_DATASOURCE_PASSWORD echo datasource-received',
      'if defined KC_BOOTSTRAP_ADMIN_PASSWORD echo extra-secret-passed',
      'exit /b 7'
    ))
    try {
      $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\run-backend.ps1') -MavenArgs '-Dx=1' 2>&1 | Out-String
      $LASTEXITCODE | Should Be 7
      $output | Should Match 'args=spring-boot:run -Dx=1'
      $output | Should Match 'secret-received'
      $output | Should Match 'datasource-received'
      $output | Should Not Match 'extra-secret-passed'
      foreach ($value in $secretValues) { $output | Should Not Match ([regex]::Escape($value)) }
    } finally { Remove-Item -Recurse -Force $root }
  }

  It 'no deja ninguna variable en el entorno de quien lo ejecuta' {
    $file = New-TestEnvFile (Get-CompleteEnvLines)
    try {
      $probe = "& '$script' -EnvFile '$file' -ListVariables | Out-Null; " +
        "if (`$env:DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET -or `$env:DENTASOFT_DB_APP_PASSWORD) { exit 3 } else { exit 0 }"
      & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $probe | Out-Null
      $LASTEXITCODE | Should Be 0
    } finally { Remove-Item -Recurse -Force (Split-Path $file) }
  }
}
