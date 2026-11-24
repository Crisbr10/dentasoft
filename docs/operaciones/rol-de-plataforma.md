# Rol de base de datos de la plataforma

La administración de clínicas de la plataforma (M01-T05) se conecta a PostgreSQL con el rol `dentasoft_platform` (ver [ADR 0011](../adr/0011-rol-de-plataforma-en-base-de-datos.md)). El rol lo crea el superusuario **una vez por entorno y antes de desplegar la versión con la migración V002**. Si no existe, V002 falla y la aplicación no arranca.

Los permisos sobre las tablas y las políticas no se crean a mano: los aplica V002, que ejecuta Flyway como `dentasoft_owner`.

## Local

En un volumen nuevo, el contenedor de PostgreSQL ejecuta `infra/postgres/init/02-platform-role.sh` en el primer arranque. Si la base ya existía:

1. Añade `DENTASOFT_DB_PLATFORM_PASSWORD` a `infra/.env`, con un valor inventado, como el resto de contraseñas locales.
2. Recrea el contenedor de PostgreSQL para que reciba la variable. El volumen y sus datos se mantienen. Sin `--wait`: el healthcheck exige el rol, que aún no existe.

   ```powershell
   cd infra; docker compose -f compose.yml -f compose.local.yml up -d postgres
   ```

3. Ejecuta el script dentro del contenedor, como superusuario. Es idempotente:

   ```powershell
   docker compose -f compose.yml -f compose.local.yml exec postgres bash /docker-entrypoint-initdb.d/02-platform-role.sh
   ```

4. Comprueba que todo está sano: `docker compose -f compose.yml -f compose.local.yml up -d --wait`.

## Pruebas y producción

Sigue estos pasos con el superusuario de la plataforma de PostgreSQL. No uses `dentasoft_owner` ni `dentasoft_app`.

1. **Genera una contraseña** larga y aleatoria (por ejemplo, `openssl rand -base64 32`) y guárdala en el gestor de secretos del entorno. No se escribe en el repositorio.
2. **Crea el rol** con `psql`, conectado a la base `postgres`. `\password` pide la contraseña sin mostrarla y la envía cifrada, así que no queda en el historial ni en los logs del servidor:

   ```sql
   CREATE ROLE dentasoft_platform LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
   \password dentasoft_platform
   GRANT CONNECT ON DATABASE dentasoft TO dentasoft_platform;
   \connect dentasoft
   GRANT USAGE ON SCHEMA public TO dentasoft_platform;
   ```

   Otra opción es copiar `02-platform-role.sh` al servidor y ejecutarlo con `DENTASOFT_DB_PLATFORM_PASSWORD` y `POSTGRES_USER` en el entorno. Hace lo mismo.
3. **Comprueba el rol**: tiene que salir `f` en `rolsuper` y en `rolbypassrls`, y ninguna fila en la segunda consulta.

   ```sql
   SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'dentasoft_platform';
   SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
   WHERE m.member = 'dentasoft_platform'::regrole OR m.roleid = 'dentasoft_platform'::regrole;
   ```

   Nunca concedas este rol a otro (`GRANT dentasoft_platform TO …`): `dentasoft_app` podría hacer `SET ROLE` y ver todas las clínicas.
4. **Configura el backend** con estas variables y despliega:

   | Variable | Valor |
   | --- | --- |
   | `DENTASOFT_PLATFORM_DATASOURCE_USERNAME` | `dentasoft_platform` |
   | `DENTASOFT_PLATFORM_DATASOURCE_PASSWORD` | la contraseña del paso 1 |
   | `DENTASOFT_PLATFORM_DATASOURCE_URL` | opcional: por defecto, la de `SPRING_DATASOURCE_URL` |

   Sin usuario o sin contraseña, el backend no arranca y el error nombra la variable que falta.

## Cambiar la contraseña

```sql
\password dentasoft_platform
```

Después actualiza `DENTASOFT_PLATFORM_DATASOURCE_PASSWORD` y reinicia el backend. Las conexiones abiertas siguen funcionando hasta que el pool las renueva.
