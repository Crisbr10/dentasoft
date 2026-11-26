# 0015 — Roles de clínica en la base de datos y usuarios aislados por usuario

Fecha: 2026-11-26 · Estado: aceptada

## Contexto

Un usuario trabaja en una o varias clínicas, con un rol en cada una (M02-T03). Había que decidir dónde vive ese rol y cómo se protegen los datos de los usuarios. Las restricciones eran cinco:

- **Keycloak ya tiene organizaciones**: el realm las tiene activadas (M02-T01) y cada clínica guarda su `keycloak_org_id`.
- **Desactivar es inmediato**: una membresía desactivada tiene que dejar de valer en el acto (criterio de M02-T05), y un token de acceso vive 5 minutos.
- **Datos mínimos**: Keycloak ya tiene los datos personales del usuario (nombre, email, teléfono). Copiarlos duplicaría datos personales sin necesidad.
- **Antes de la clínica activa**: las membresías de un usuario se leen antes de que haya clínica activa, para `GET /api/v1/me` y para validar la clínica en M02-T04. Con eso, `tenant_isolation` no sirve.
- **RLS en todas las tablas**: el test guardián exige Row Level Security en todas las tablas de `public` (ADR 0002), y `user_account` no es de ninguna clínica.

## Decisión

- **Fuente de verdad**:
  - Los roles de clínica están en `clinic_membership`, con un solo rol por clínica (`UNIQUE (tenant_id, user_id)`).
  - Los roles son un enumerado cerrado (`identity.ClinicRole`), igual al `CHECK` de la tabla y a las columnas de `docs/plan/configuracion.md`. Lo comprueba `ClinicRoleTests`.
  - Del token solo sale el `sub` y el rol del realm `PLATFORM_ADMIN` (ADR 0014).
- **Papel de las organizaciones de Keycloak**: son un espejo para lo que Keycloak hace bien:
  - La invitación por email y el alta del usuario en la clínica (M02-T05).
  - En el futuro, un proveedor de identidad propio por clínica.
  - El backend no autoriza nada con el claim `organization`, que además es un scope opcional del realm. Si Keycloak y la base de datos no coinciden, manda la base de datos.
- **`user_account` mínimo**:
  - Guarda el `id`, que es el `sub` de Keycloak, más el `status` (`ACTIVE`, `DISABLED`), las fechas, las columnas de auditoría y la versión. Nada personal: el frontend lee el nombre y el email del ID token.
  - El `id` no es un UUID v7 generado por la aplicación. Es una excepción a esa regla: es lo que ya guardan las columnas de auditoría y se conoce desde el token sin buscar nada.
- **Usuario en cada transacción**: el dialecto JPA fija `app.user_id` con `set_config(…, true)`, igual que la clínica (ADR 0010). Lo hace con clínica o sin ella, también en las transacciones `REQUIRES_NEW` de la misma petición. Los procesos sin usuario no lo fijan.
- **Políticas** (V007):
  - `user_account` usa `user_isolation` (`id = app.user_id`), creada con `enable_user_isolation`. Cada usuario solo ve y crea su fila, y la aplicación no tiene `UPDATE` sobre ella.
  - `clinic_membership` usa `tenant_isolation` y además `own_memberships` (`FOR SELECT TO dentasoft_app`, `user_id = app.user_id`). Sin clínica activa, el usuario ve solo sus membresías. Con la clínica A activa, ve las de A más las suyas en otras clínicas. Las escrituras siguen limitadas a la clínica activa, así que nadie puede darse de alta, cambiar de rol ni reactivarse en otra clínica. La aplicación no tiene `UPDATE` sobre `user_id`, así que una membresía nunca cambia de usuario.
  - `clinic` añade `member_select` (`FOR SELECT TO dentasoft_app`): el usuario lee las clínicas en las que tiene una membresía activa. Al desactivarla, deja de leer esa clínica.
  - El test guardián declara cada política y cada tabla aislada por usuario con su motivo.
- **Alta en el primer acceso**:
  - Un filtro posterior a la autenticación ejecuta `INSERT … ON CONFLICT (id) DO NOTHING`, así que dos primeras peticiones simultáneas no fallan.
  - Cada instancia recuerda los últimos 10 000 usuarios vistos para no repetir el insert en cada petición.
- **Clínica activa** (M02-T04):
  - Cada petición a `/api/v1/clinic/**` indica su clínica en la cabecera `X-Clinic-Id`.
  - Un filtro posterior a Spring Security y al alta del usuario (`identity.internal.ActiveClinicFilter`) la comprueba contra la base de datos en cada petición, sin caché: membresía activa, cuenta `ACTIVE` y clínica no suspendida.
  - La comprobación usa una transacción de solo lectura propia, en la conexión de `dentasoft_app` con `app.user_id`, nunca en la de plataforma. Así `own_memberships` y `member_select` también la imponen en PostgreSQL.
  - Si pasa, el resto de la petición corre dentro de `TenantContext` con `ROLE_<rol>` añadido a las autoridades del token, solo para esa petición.
  - Sin cabecera, con la cabecera repetida o con un valor que no es un UUID: 400.
  - Clínica inexistente, ajena o suspendida, membresía inactiva o cuenta desactivada: el mismo 403 que cualquier otra denegación, registrado en el log solo con los ids del usuario y de la clínica.
  - `PLATFORM_ADMIN` sin membresía recibe ese mismo 403.
- **Los roles de clínica solo valen con `@PreAuthorize` en los servicios**: se añaden después de que Spring Security haya autorizado la petición, así que una regla por URL (`requestMatchers(…).hasRole("ODONTOLOGO")`) nunca los ve y deniega siempre. Las cadenas de seguridad solo piden un usuario autenticado.
- **Membresías**: no hay endpoint para crearlas: llegan con las invitaciones (M02-T05). `ClinicMembership` lleva `@AuditedEntity` desde ya.
- **Lotes lanzados por un usuario**: si un proceso por lotes se lanza desde una petición, por ejemplo de un `PLATFORM_ADMIN`, se audita como ese usuario (`USER`, con su `sub`). Así se sabe quién lo lanzó. Los que lanza el planificador siguen siendo `SYSTEM` (ADR 0013).

## Consecuencias

- **Lo que se gana**:
  - Cambiar un rol o desactivar una membresía vale en la siguiente petición, sin esperar a que caduque el token.
  - Los permisos se prueban con SQL y con tests de aislamiento, como el resto de datos de clínica.
  - No se duplican datos personales.
- **Lo que cuesta**:
  - Mantener las organizaciones de Keycloak alineadas con las membresías es trabajo de M02-T05.
  - El guardián de RLS tiene dos categorías nuevas: tablas aisladas por usuario y políticas de lectura de la aplicación.
  - Cada nueva política de lectura que amplíe lo que ve `dentasoft_app` necesita su propio motivo.
  - Cada petición a la API de clínica hace dos lecturas por clave para comprobar la clínica activa.
  - La clínica y el rol no pasan a otros hilos, así que ningún endpoint responde de forma asíncrona (`DeferredResult`, `Callable`, `CompletableFuture`…). Lo comprueba `NoAsyncEndpointsTests`.
- **Resuelto en M02-T05** ([ADR 0016](0016-admin-api-de-keycloak-desde-el-backend.md)): con `user_isolation`, una invitación no podía crear la fila de `user_account` de otro usuario, y la FK `clinic_membership.user_id → user_account` la exige. Se mantiene la FK, y V008 añade una política de solo `INSERT` (`invited_insert`): cuenta `ACTIVE`, dentro de una clínica activa y a nombre de quien invita.

## Alternativas descartadas

- **Roles en Keycloak (por organización o como roles de cliente)**: Keycloak no tiene roles por organización. Además, el token tardaría hasta 5 minutos en reflejar una baja y crecería con cada clínica.
- **Copiar nombre y email en `user_account`**: duplica datos personales que ya están en Keycloak y que habría que mantener sincronizados.
- **Leer las membresías con una función `SECURITY DEFINER`**: se salta RLS y obliga a revisar a mano cada consulta que pase por ella.
- **Alta del usuario con un `SELECT` previo y luego `INSERT`**: dos peticiones simultáneas chocan con la clave primaria.
