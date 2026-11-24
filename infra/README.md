# Infraestructura local

Servicios de apoyo de Dentasoft para desarrollo, con Docker Compose.

| Fichero | Para qué |
| --- | --- |
| `compose.yml` | Servicios comunes a todos los entornos. No se usa solo. |
| `compose.local.yml` | Desarrollo local: puertos en `127.0.0.1`, Keycloak en `start-dev` y Mailpit. |
| `.env.example` | Versiones fijadas de las imágenes y contraseñas de ejemplo (falsas). |
| `postgres/init/01-init.sh` | Crea bases, roles y la extensión `vector` (solo en el primer arranque). |
| `postgres/init/02-platform-role.sh` | Crea el rol de plataforma `dentasoft_platform` (primer arranque; se puede repetir a mano). |

## Arrancar

```powershell
cd infra
Copy-Item .env.example .env   # solo la primera vez; cambia las contraseñas
docker compose -f compose.yml -f compose.local.yml up -d --wait
```

`--wait` termina cuando todos los servicios están sanos (o falla si alguno no lo consigue). `.env` está en `.gitignore`: nunca lo subas.

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

Las tablas y sus permisos son cosa de las migraciones de Flyway (M01-T01), y el realm de Keycloak de M02-T01.

> **Importante**: PostgreSQL ejecuta el script **solo cuando el volumen de datos está vacío**, es decir, en el primer arranque. Si cambias el script o las contraseñas de la base en `.env`, no se aplican solas: hay que borrar el volumen, **lo que elimina todos los datos locales** (también los de Keycloak):
>
> ```powershell
> docker compose -f compose.yml -f compose.local.yml down
> docker volume rm dentasoft_postgres-data
> docker compose -f compose.yml -f compose.local.yml up -d --wait
> ```

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
