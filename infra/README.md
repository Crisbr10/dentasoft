# Infraestructura local

Servicios de apoyo de Dentasoft para desarrollo, con Docker Compose.

| Fichero | Para qué |
| --- | --- |
| `compose.yml` | Servicios comunes a todos los entornos. No se usa solo. |
| `compose.local.yml` | Desarrollo local: puertos en `127.0.0.1`, Keycloak en `start-dev` y Mailpit. |
| `.env.example` | Versiones fijadas de las imágenes y contraseñas de ejemplo (falsas). |
| `postgres/init/01-init.sh` | Crea bases, roles y la extensión `vector` (solo en el primer arranque). |
| `postgres/init/02-platform-role.sh` | Crea el rol de plataforma `dentasoft_platform` (primer arranque; se puede repetir a mano). |
| `keycloak/realm-dentasoft.json` | Realm `dentasoft` de Keycloak, importado si aún no existe. |
| `keycloak/local/dentasoft-users-0.json` | Usuarios de prueba inventados. Solo en local. |

## Arrancar

```powershell
cd infra
Copy-Item .env.example .env   # solo la primera vez; cambia las contraseñas
docker compose -f compose.yml -f compose.local.yml up -d --wait
```

`--wait` termina cuando todos los servicios están sanos (o falla si alguno no lo consigue). `.env` está en `.gitignore`: nunca lo subas.

Si tu `.env` es de antes de una tarea que añade variables (por ejemplo, las del realm de Keycloak de M02-T01), Compose no arranca ningún servicio y dice cuál falta (`required variable … is missing a value`). Copia las líneas nuevas de `.env.example` a tu `.env` y vuelve a lanzar el comando.

Para parar sin borrar datos:

```powershell
docker compose -f compose.yml -f compose.local.yml down
```

### Antivirus (opcional)

ClamAV va en el perfil `antivirus` porque consume entre 1,5 y 2 GB de RAM y no hace falta hasta M05-T02 (subida de documentos). Para levantarlo:

```powershell
docker compose -f compose.yml -f compose.local.yml --profile antivirus up -d --wait
```

El primer arranque descarga la base de firmas y puede tardar varios minutos.

### Backend en contenedor (opcional)

El perfil `app` construye la imagen del backend desde `backend/` y la arranca junto a la infraestructura, con el perfil `local` de Spring. Usa el puerto 8080, igual que `mvnw spring-boot:run`: arranca solo uno de los dos.

```powershell
docker compose -f compose.yml -f compose.local.yml --profile app up -d --wait --build
curl http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
```

- Las URL públicas (`DENTASOFT_URLS_*`) apuntan a `localhost`, que es desde donde las ve el navegador. Los servicios de apoyo se alcanzan por su nombre dentro de la red de Docker (`postgres`, `redis`, `seaweedfs`, `mailpit`). Esas variables quedan preparadas aunque el backend aún no las use: llegan con M01-T01 (base de datos), M00-T08 (Redis), M05 (S3) y M07 (email), y esas tareas pueden renombrarlas.
- Los tokens llevan como emisor la URL pública de Keycloak (`DENTASOFT_URLS_AUTH`), pero desde el contenedor sus claves de firma están en `http://keycloak:8080`: por eso `compose.local.yml` fija `DENTASOFT_AUTH_JWK_SET_URI`. Con `mvnw spring-boot:run` no hace falta, porque las dos direcciones coinciden.
- El contenedor corre con el UID 10001, el sistema de ficheros en solo lectura (solo `/tmp` es escribible), sin *capabilities* y con `no-new-privileges`.
- `stop_grace_period` (40 s) es mayor que el apagado ordenado del backend (`SHUTDOWN_TIMEOUT`, 30 s).

Fuera de local no se construye la imagen: se usa la que publica la CI en GHCR (ver [integración continua](../docs/operaciones/integracion-continua.md#imagen-del-backend)). La imagen se describe en [backend/README.md](../backend/README.md#imagen-docker).

## URLs locales

| Servicio | URL | Acceso |
| --- | --- | --- |
| PostgreSQL | `localhost:5432`, base `dentasoft` | `dentasoft_owner` (migraciones) y `dentasoft_app` (aplicación) |
| Keycloak | <http://localhost:8180> | `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` |
| Redis | `localhost:6379` | `DENTASOFT_REDIS_PASSWORD` |
| S3 (SeaweedFS) | <http://localhost:8333>, bucket `dentasoft-docs` | `DENTASOFT_S3_ACCESS_KEY` / `DENTASOFT_S3_SECRET_KEY` |
| Mailpit (SMTP) | `localhost:1025` | Sin autenticación |
| Mailpit (web) | <http://localhost:8025> | Sin autenticación |
| ClamAV (perfil `antivirus`) | `localhost:3310` | Protocolo de clamd |

Todos los puertos se publican solo en `127.0.0.1`: no son accesibles desde la red.

## Base de datos

El script `postgres/init/01-init.sh` crea:

- Los roles `dentasoft_owner` (dueño de la base `dentasoft` y del esquema `public`) y `dentasoft_app` (solo `CONNECT` y `USAGE` en `public`, `NOBYPASSRLS`, no puede crear tablas).
- La base `keycloak` con su propio rol `keycloak`.
- La extensión `vector` en `dentasoft`.

El script `postgres/init/02-platform-role.sh` crea el rol `dentasoft_platform`, con el que la administración de la plataforma ve todas las clínicas (ADR 0011). Necesita `DENTASOFT_DB_PLATFORM_PASSWORD` en `.env`. A diferencia de `01-init.sh`, se puede ejecutar sobre una base que ya existe, sin borrar el volumen: ver [`docs/operaciones/rol-de-plataforma.md`](../docs/operaciones/rol-de-plataforma.md).

Las tablas y sus permisos son cosa de las migraciones de Flyway (M01-T01); el realm de Keycloak se describe más abajo.

> **Importante**: PostgreSQL ejecuta el script **solo cuando el volumen de datos está vacío**, es decir, en el primer arranque. Si cambias el script o las contraseñas de la base en `.env`, no se aplican solas: hay que borrar el volumen, **lo que elimina todos los datos locales** (también los de Keycloak):
>
> ```powershell
> docker compose -f compose.yml -f compose.local.yml down
> docker volume rm dentasoft_postgres-data
> docker compose -f compose.yml -f compose.local.yml up -d --wait
> ```

## Realm de Keycloak

Keycloak arranca con `--import-realm` y crea el realm `dentasoft` desde `keycloak/realm-dentasoft.json` (M02-T01). `up --wait` termina cuando Keycloak está sano, y para entonces el realm ya está importado: no hay pasos manuales.

Qué configura el realm:

| Ajuste | Valor |
| --- | --- |
| Cliente `dentasoft-web` | Público, solo *authorization code* con PKCE S256. Sin *password grant*, *implicit* ni *device flow* |
| Redirect URI, origen web y *post-logout* | Exactos, sin comodines: `${DENTASOFT_URLS_APP}/` y `${DENTASOFT_URLS_APP}` |
| Scope `dentasoft-api` | Añade `dentasoft-api` a la audiencia (`aud`) del token de acceso |
| Roles en el token | Solo `PLATFORM_ADMIN` (`fullScopeAllowed: false`). Los roles de clínica viven en la base de datos (M02-T03) |
| Login | Contraseña y OTP (TOTP) obligatorios. Quien no tiene OTP lo configura en su primer login |
| *Password grant* | Denegado en todo el realm, también para `admin-cli` |
| Tokens | Acceso de 5 minutos. Cada refresh token sirve una sola vez (`revokeRefreshToken`, `refreshTokenMaxReuse: 0`) |
| Sesión | 30 minutos de inactividad y 10 horas como máximo |
| Fuerza bruta | Bloqueo temporal tras 5 fallos, con espera creciente hasta 15 minutos |
| Contraseñas | 12 caracteres o más (128 como máximo), distinta del usuario y del email, sin repetir las 5 últimas |
| Altas | Registro desactivado: crean los usuarios la clínica o la plataforma. Verificación de email y recuperación de contraseña por SMTP |
| Organizaciones | Activadas |
| Cliente `dentasoft-backend` | Confidencial, solo con cuenta de servicio (*client credentials*), para la API de administración de Keycloak (M02-T05). Ver más abajo |
| Grupo `clinic-users` | Todos los usuarios de clínica, con `id` fijo. Los administradores de plataforma nunca están en él |
| Permisos finos de administración | Activados (FGAP v2): la cuenta de servicio del backend solo ve y gestiona a los miembros de `clinic-users` |
| Eventos de seguridad | Login y administración guardados 90 días, sin el detalle de las peticiones de administración. Sin *listener* de log, para no escribir usuarios ni IP en el log del contenedor |

El JSON no lleva dominios ni secretos: Keycloak sustituye al importar los `${DENTASOFT_...}` por las variables del contenedor, que `compose.yml` toma de `.env`:

| Variable | Para qué | Local |
| --- | --- | --- |
| `DENTASOFT_URLS_APP` | Redirect URI, origen web y *post-logout* de `dentasoft-web` | `http://localhost:4200` |
| `DENTASOFT_MAIL_FROM` | Remitente de los emails de Keycloak | Uno inventado, `.test` |
| `DENTASOFT_KC_SMTP_HOST` / `_PORT` | Servidor SMTP | `mailpit` / `1025` |
| `DENTASOFT_KC_SMTP_AUTH` / `_STARTTLS` / `_SSL` | Autenticación y cifrado SMTP | `false` |
| `DENTASOFT_KC_SMTP_USER` / `_PASSWORD` | Credenciales SMTP (opcionales) | Vacías |
| `DENTASOFT_KC_LOCAL_USERS_PASSWORD` | Contraseña de los usuarios de prueba (solo `compose.local.yml`) | Cualquiera que cumpla la política |
| `DENTASOFT_KC_BACKEND_CLIENT_SECRET` | Secreto del cliente `dentasoft-backend`. `compose.local.yml` pasa el mismo valor al backend como `DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET` | Uno aleatorio, largo |

`KeycloakRealmFileTests` (backend) lee los ficheros sin arrancar Keycloak. Falla si el JSON tiene un dominio, un comodín en las redirecciones, un secreto fuera de una variable o una variable que Compose no pasa a Keycloak. También falla si cambia alguno de los valores de la tabla: el cliente, la audiencia, los roles, el flujo de login con OTP, el *password grant*, los tokens, las sesiones, la fuerza bruta, la política de contraseñas, las altas y los eventos. Y si `dentasoft-backend` deja de ser solo una cuenta de servicio, si recibe más roles de `realm-management` o si los permisos finos dejan de limitarla a `clinic-users`. Que Keycloak importe el realm y pida el OTP en el primer login se comprobó a mano con Keycloak 26.8.2. Si cambias el realm o subes la versión de la imagen, repite esa comprobación (`up -d --wait` y un login).

### Cliente `dentasoft-backend` y permisos finos (FGAP v2)

El backend invita usuarios, los añade a la organización de su clínica y cierra sus sesiones con la API de administración de Keycloak (M02-T05). Para eso usa el cliente confidencial `dentasoft-backend`, sin login de navegador ni redirecciones, nunca el administrador del realm `master`. Su cuenta de servicio tiene solo los roles `query-users`, `query-groups` y `manage-organizations` de `realm-management`, y el scope `kc-admin-api`, que añade la audiencia que exige la API.

No tiene `manage-users` a propósito: con ese rol, la cuenta de servicio podía asignar `PLATFORM_ADMIN` a cualquiera (también a sí misma) o resetear la contraseña y borrar el OTP de un administrador de plataforma (comprobado con Keycloak 26.8.2). En su lugar, los permisos finos de administración (FGAP v2) le dejan ver y gestionar solo a los miembros del grupo `clinic-users`: una política de usuario sobre `service-account-dentasoft-backend` y un permiso sobre ese grupo con `view`, `view-members`, `manage-members` y `manage-membership`. Solo puede crear usuarios dentro del grupo, y para ella un administrador de plataforma no existe: no lo encuentra al buscar y recibe un 403 si intenta tocarlo. Por eso los administradores de plataforma **nunca** se añaden a `clinic-users`. El permiso nombra el grupo por su `id`, que está fijo en el JSON.

La comprobación 5 del issue #136 (el realm en la CI) verificará contra un Keycloak real que la cuenta de servicio recibe 403 al asignar `PLATFORM_ADMIN` o al tocar a un usuario de plataforma, para que un cambio en el JSON que rompa estos permisos haga fallar la CI.

### Tokens en el backend

El backend valida los tokens de acceso como Resource Server (M02-T02), sin pedir nada a Keycloak al arrancar:

| Comprobación | Valor |
| --- | --- |
| Emisor | `${DENTASOFT_URLS_AUTH}/realms/${DENTASOFT_AUTH_REALM}` (el realm es `dentasoft` si no se fija) |
| Claves | `DENTASOFT_AUTH_JWK_SET_URI`; si no se fija, sale del emisor (`.../protocol/openid-connect/certs`). Se piden con el primer token, con 2 s para conectar y 3 s para leer, se guardan 5 minutos y se vuelven a pedir como mucho dos veces cada 30 s |
| Firma | Solo RS256: se rechazan `alg: none`, HS256 y cualquier otro algoritmo |
| Audiencia | Debe incluir `dentasoft-api` |
| Caducidad | `exp` obligatorio, con 30 s de tolerancia de reloj |
| Usuario | `sub` debe ser un UUID: es el que guardan `created_by`, `updated_by` y el registro de auditoría |
| Tipo | `typ` debe ser `Bearer`: un ID token o un refresh token no valen como token de acceso |
| Roles | Solo los roles del realm que el backend conoce (`PLATFORM_ADMIN` → `ROLE_PLATFORM_ADMIN`) |

Sin token válido, la respuesta es un 401 con `ProblemDetail` y `WWW-Authenticate: Bearer`, el mismo sea cual sea el motivo. El límite anónimo por IP (`DENTASOFT_RATE_LIMIT_ANONYMOUS_PER_MINUTE`) se aplica antes de validar el token y cuenta todas las peticiones que acaban sin usuario, también los 401: una avalancha de tokens falsos acaba en 429 sin llegar a comprobar las firmas. Si Keycloak no responde, también es un 401, y el backend lo registra en el log. Las únicas rutas públicas son `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness`, además de `/v3/api-docs/**` y `/swagger-ui/**` mientras `springdoc.api-docs.enabled` esté activo (solo en el perfil `local`, para `pnpm api:gen`).

### Cambios en el realm

> **Importante**: `--import-realm` solo importa realms que **no existen**. Si cambias `realm-dentasoft.json` o las variables de `.env` después del primer arranque, el realm que ya está en la base de datos **no cambia**.

- **En local**, para reimportarlo de cero, borra el realm desde la consola de administración (<http://localhost:8180>, realm `dentasoft` → *Realm settings* → *Action* → *Delete*) y reinicia Keycloak: `docker compose -f compose.yml -f compose.local.yml restart keycloak`. Se pierden los usuarios, las sesiones y el OTP configurado de ese realm, pero no el resto de la base de datos.
- **En pruebas y producción** no se borra el realm: los cambios se aplican en la consola de administración (o con `kcadm.sh`) y también en el JSON, para que un entorno nuevo nazca igual. Ver la nota de M03-T02 y [cambio de dominio](../docs/operaciones/cambio-de-dominio.md).

### Usuarios de prueba (solo local)

`compose.local.yml` monta además `keycloak/local/dentasoft-users-0.json`, que Keycloak importa junto con el realm. Son personas inventadas con dominios reservados `.test` (los emails llegan a Mailpit), y todos usan la contraseña `DENTASOFT_KC_LOCAL_USERS_PASSWORD` de `.env`:

| Usuario | Rol en Keycloak | Para probar |
| --- | --- | --- |
| `plataforma@dentasoft.test` | `PLATFORM_ADMIN` | Administración de clínicas (M01) |
| `admin@clinica-norte.test` | — | `ADMIN_CLINICA` |
| `odontologa@clinica-norte.test` | — | `ODONTOLOGO` |
| `recepcion@clinica-norte.test` | — | `RECEPCION` |

Los roles de clínica no están en Keycloak, sino en las membresías de la base de datos, que llegan con M02-T03: hasta entonces estos usuarios solo pueden iniciar sesión. En el primer login, Keycloak pide configurar el OTP con una app de autenticación (FreeOTP, Google Authenticator…).

Los tres usuarios de clínica están en el grupo `clinic-users`, y `plataforma@dentasoft.test` no: `KeycloakRealmFileTests` lo comprueba.

Este fichero no se monta fuera de local: `KeycloakRealmFileTests` comprueba que `compose.yml` no lo referencia.

## Almacenamiento S3: SeaweedFS en lugar de MinIO

En local, el almacenamiento S3 es SeaweedFS en lugar de MinIO. El motivo y las alternativas están en el [ADR 0007](../docs/adr/0007-s3-local-seaweedfs.md).

El contenedor `seaweedfs-init` (AWS CLI) crea el bucket `dentasoft-docs`, activa el versionado y se queda en reposo; está sano cuando el bucket está listo. Se puede repetir sin problemas. Si la configuración falla, lo reintenta 3 veces y después se queda parado con el error: míralo con `docker compose -f compose.yml -f compose.local.yml logs seaweedfs-init`.

Solo la API S3 (puerto 8333) comprueba las credenciales. Los demás componentes que arranca SeaweedFS (master, volume y filer) escuchan sin autenticación dentro de la red de Docker. Por eso `compose.local.yml` publica solo el 8333, y en `127.0.0.1`: no publiques ningún otro puerto de SeaweedFS.

## Comprobaciones

```powershell
$c = "-f", "compose.yml", "-f", "compose.local.yml"

# Ficheros válidos (con el perfil opcional incluido)
docker compose @c --profile antivirus config --quiet

# Estado y salud de los servicios
docker compose @c ps

# Roles: ninguno se salta RLS
docker compose @c exec postgres psql -U postgres -d dentasoft -c "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname LIKE 'dentasoft%';"

# Extensión vector instalada
docker compose @c exec postgres psql -U postgres -d dentasoft -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"

# dentasoft_app no puede crear tablas en public (debe fallar con "permission denied for schema public").
# El SQL va por la entrada estándar: PowerShell 5.1 rompe las comillas dobles anidadas dentro de sh -c.
"CREATE TABLE probe (id int);" | docker compose @c exec -T postgres sh -c 'PGPASSWORD=$DENTASOFT_DB_APP_PASSWORD psql -h 127.0.0.1 -U dentasoft_app -d dentasoft -v ON_ERROR_STOP=1'

# Versionado del bucket (debe responder "Enabled")
docker compose @c exec seaweedfs-init aws --endpoint-url http://seaweedfs:8333 s3api get-bucket-versioning --bucket dentasoft-docs
```
