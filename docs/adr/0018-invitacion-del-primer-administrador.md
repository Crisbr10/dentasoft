# 0018 — La plataforma invita al primer administrador de una clínica

Fecha: 2026-11-28 · Estado: aceptada

## Contexto

Una clínica recién creada (M01-T05) no tiene miembros. Solo un `ADMIN_CLINICA` aceptado de la clínica invita a otras personas (M02-T05, V010), así que nadie podía invitar a su primer administrador.

Lo invita `PLATFORM_ADMIN` (M02-T11). La plataforma trabaja con su propio rol de base de datos, `dentasoft_platform`, que no ve datos clínicos (ADR 0011). La política `invited_insert` de `dentasoft_app` (V010) no se abre: aflojarla afectaría a todas las invitaciones de todas las clínicas.

Había que decidir con qué rol se crea la membresía, cómo impone PostgreSQL «solo mientras la clínica no tenga administrador» también con peticiones simultáneas, qué puede leer la plataforma y cómo queda en el registro de auditoría.

## Decisión

- **`dentasoft_platform` crea la membresía**, con políticas propias `TO dentasoft_platform` (V013). Para `dentasoft_app` no cambia nada.
- **Qué puede hacer la plataforma, y nada más**:
  - **en `user_account`**: `INSERT` de una cuenta activa en su nombre (`created_by = app.user_id`), con la política `platform_insert`, y `EXECUTE` de `create_invited_user_account` (V008, `SECURITY INVOKER`: se ejecuta con esa misma política). No lee, cambia ni borra cuentas;
  - **en `clinic_membership`**:
    - `SELECT` de `id`, `tenant_id`, `user_id`, `role`, `active`, `accepted_at`, `updated_at` y `version`, solo de las filas `ADMIN_CLINICA` (`platform_select`). Ve los ids de usuario de los administradores de todas las clínicas: nombres y emails siguen en Keycloak;
    - `INSERT` de una membresía `ADMIN_CLINICA` activa y pendiente, en su nombre y nunca para sí mismo (`platform_insert`): el administrador de plataforma usa otra cuenta para trabajar en una clínica (ADR 0016). No tiene permiso sobre `accepted_at`;
    - `UPDATE` solo de `active` y de las columnas de auditoría, y solo de filas pendientes, antes y después del cambio (`platform_update`, `accepted_at IS NULL` en `USING` y `WITH CHECK`). Así cancela una invitación o vuelve a activar una cancelada. Nunca toca `accepted_at` ni `role`: aceptar es solo de la persona invitada (V009 y V011). Una membresía aceptada queda fuera de su alcance;
  - **en `audit_log`**: `platform_insert` admite también `entity_type = 'CLINIC_MEMBERSHIP'` cuando `entity_id` es una membresía de esa misma clínica (`tenant_id`).
- **Políticas restrictivas para que nada más se sume.** Las políticas anteriores son permisivas, y PostgreSQL las combina con `OR` con `tenant_isolation` y `user_isolation`, que son `TO PUBLIC`. Con `app.tenant_id` fijado, la plataforma alcanzaría todas las membresías de esa clínica y podría escribir cualquier entrada de auditoría. Con `app.user_id`, podría crear su propia cuenta con cualquier estado. La aplicación nunca fija una clínica para la plataforma, pero cualquier rol puede llamar a `set_config`. Por eso V013 añade políticas `AS RESTRICTIVE TO dentasoft_platform`, que PostgreSQL combina con `AND`:
  - `platform_without_clinic` en `clinic_membership`, `user_account` y `audit_log`: sin clínica activa o nada;
  - `platform_insert_check` en `user_account`: solo cuentas activas creadas en su nombre.
- **Nunca para sí mismo**: ni al invitar ni al reactivar (`user_id <> app.user_id` en `platform_insert` y `platform_update`).
- **El trigger `platform_first_admin` impone la regla.** Cuando la plataforma activa una membresía de administrador (nueva o cancelada) o la cancela, el trigger bloquea antes la fila de `clinic` con `FOR NO KEY UPDATE`, que no bloquea las comprobaciones de claves foráneas de otras tablas. Después:
  - rechaza activarla si la clínica ya tiene un `ADMIN_CLINICA` activo, aceptado o pendiente (`unique_violation`, restricción `clinic_first_admin`);
  - rechaza cancelarla si la clínica ya tiene un administrador aceptado (`insufficient_privilege`): las invitaciones pendientes que quedan son de la propia clínica (M02-T05) y la plataforma no las toca. El servicio responde 404 antes de llegar ahí.

  Dos cambios simultáneos se ejecutan uno tras otro: en `READ COMMITTED`, el segundo ve lo que confirmó el primero. En `REPEATABLE READ` leería una foto anterior, así que el trigger se niega a ejecutarse con ese aislamiento (`SERIALIZABLE` detecta el conflicto por sí mismo). Es `SECURITY INVOKER` y solo actúa para `dentasoft_platform`: un administrador de la clínica sigue invitando y desactivando a otros administradores.
- **Las invitaciones pendientes cuentan.** Con una pendiente, la plataforma no puede crear otra: la reenvía o la cancela. Reenviar no cambia la membresía, solo se registra en `audit_log`.
- **El módulo `platform` escribe en la base de datos e `identity` habla con Keycloak.** `PlatformAdminMembership` mapea exactamente las columnas permitidas: las de auditoría se escriben, pero se leen como `NULL` (`@ColumnTransformer`), porque la plataforma no puede leerlas. La parte de Keycloak pasa por la API pública `identity.FirstAdminInvitees`: buscar o crear al usuario en `clinic-users`, enviar el email y aplicar la cuota diaria de invitaciones de la clínica. Nunca usa clases internas de `identity` ni `KeycloakAdmin`. Como en M02-T05, ninguna llamada a Keycloak va dentro de una transacción.
- **API**: `GET /api/v1/platform/clinics/{id}/admin-invitation` (estado: `NONE`, `PENDING` con `invitedAt` = `updated_at`, o `ACCEPTED` con `acceptedAt` = `accepted_at`; nunca nombre ni email), `POST /api/v1/platform/clinics/{id}/admin-invitation` (invitar, 202), `…/admin-invitation/resend` (reenviar, 202) y `…/admin-invitation/cancellation` (cancelar, 204). Si la clínica ya tiene administrador o invitación pendiente, devuelve 409; sin invitación pendiente que reenviar o cancelar, 404.
- **Auditoría**: cada invitación (`CREATE`, o `UPDATE` si se reactiva), reenvío y cancelación (`UPDATE`) queda en `audit_log` con actor `PLATFORM`, `entity_type = CLINIC_MEMBERSHIP` y los subtipos `FIRST_ADMIN_INVITED`, `FIRST_ADMIN_INVITATION_RESENT` y `FIRST_ADMIN_INVITATION_CANCELLED`.

## Consecuencias

- **La regla la impone PostgreSQL, no el servicio.** Aunque falle el código, la plataforma no crea un segundo administrador ni acepta o cambia una membresía aceptada. `PlatformFirstAdminDatabaseTests` lo prueba por SQL, también con dos transacciones simultáneas.
- **La plataforma ve más que antes**: los ids de usuario de los administradores, sin datos personales. `PlatformRoleTests` compara la lista completa de permisos y `RowLevelSecurityGuardTests` cada política, permisiva o restrictiva, expresión a expresión.
- **`create_invited_user_account` es la única función que puede ejecutar la plataforma.** `PlatformRoleTests` lo fija.
- **Invitar al primer administrador gasta cuota de la clínica**: la misma de 20 al día que usan sus administradores.
- **Una clínica con su administrador aceptado ya no depende de la plataforma.** Si pierde el acceso de ese administrador (por ejemplo, porque deja la clínica sin nombrar a otro), la recuperación queda fuera de esta tarea.

## Alternativas descartadas

- Abrir `invited_insert` (V010) a `PLATFORM_ADMIN`: `dentasoft_app` no sabe si quien invita es la plataforma, salvo por una variable que la propia aplicación fijaría; un fallo abriría todas las clínicas.
- Una función `SECURITY DEFINER` con `EXECUTE` para la plataforma: el dueño está sujeto a `FORCE ROW LEVEL SECURITY`, así que tendría que fijar la clínica y el usuario dentro de la función. Es más difícil de auditar que unas políticas que PostgreSQL muestra y los tests comparan.
- Solo un índice único parcial: no expresa «ningún administrador», porque una clínica tiene varios.
- Comprobarlo solo en el servicio: dos peticiones simultáneas crearían dos «primeros» administradores.
