# Infraestructura local

Servicios de apoyo de Dentasoft para desarrollo, con Docker Compose.

| Fichero | Para qué |
| --- | --- |
| `compose.yml` | Servicios comunes a todos los entornos. No se usa solo. |
| `compose.local.yml` | Desarrollo local: puertos en `127.0.0.1`, Keycloak en `start-dev` y Mailpit. |
| `.env.example` | Versiones fijadas de las imágenes y contraseñas de ejemplo (falsas). |
| `postgres/init/01-init.sh` | Crea bases, roles y la extensión `vector` (solo en el primer arranque). |

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

Las tablas y sus permisos son cosa de las migraciones de Flyway (M01-T01), y el realm de Keycloak de M02-T01.

> **Importante**: PostgreSQL ejecuta el script **solo cuando el volumen de datos está vacío**, es decir, en el primer arranque. Si cambias el script o las contraseñas de la base en `.env`, no se aplican solas: hay que borrar el volumen, **lo que elimina todos los datos locales** (también los de Keycloak):
>
> ```powershell
> docker compose -f compose.yml -f compose.local.yml down
> docker volume rm dentasoft_postgres-data
> docker compose -f compose.yml -f compose.local.yml up -d --wait
> ```

## Almacenamiento S3: SeaweedFS en lugar de MinIO

El plan preveía MinIO, pero MinIO dejó de publicar imágenes de su edición comunitaria: a fecha de esta tarea (2026-11-23), `minio/minio` y `minio/mc` ya no existen en Docker Hub y no hay ninguna imagen pública mantenida en Quay. Fijar una etiqueta antigua supondría usar software sin parches de seguridad.

Se usa **SeaweedFS** (Apache 2.0, con publicación activa; ver [ADR 0007](../docs/adr/0007-s3-local-seaweedfs.md)) porque:

- Expone una API compatible con S3, así que el backend usará el AWS SDK v2 sin cambios, solo con otro endpoint.
- Soporta el **versionado de buckets**, que exige la tarea. Garage, la otra alternativa considerada, no lo soporta.
- Lee las credenciales del entorno (`AWS_ACCESS_KEY_ID` y `AWS_SECRET_ACCESS_KEY`) y rechaza las incorrectas.

El contenedor de un solo uso `seaweedfs-init` (AWS CLI) crea el bucket `dentasoft-docs` y activa el versionado. Se puede repetir sin problemas. En producción el almacenamiento puede ser cualquier servicio compatible con S3: este cambio solo afecta al entorno local.

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
docker compose @c run --rm --entrypoint aws seaweedfs-init --endpoint-url http://seaweedfs:8333 s3api get-bucket-versioning --bucket dentasoft-docs
```
