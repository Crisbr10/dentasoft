# 0016 — Admin API de Keycloak desde el backend e invitaciones

Fecha: 2026-11-26 · Estado: aceptada

## Contexto

Con M02-T05, `ADMIN_CLINICA` invita a personas a su clínica, les cambia el rol y desactiva membresías. Invitar exige crear el usuario en Keycloak, darlo de alta en la organización de la clínica y pedir a Keycloak que le envíe el email para verificar la dirección, poner la contraseña y configurar el OTP. Eso solo se hace con la Admin API de Keycloak. Había seis problemas:

- **Permisos de la cuenta de servicio**: con el rol `manage-users` de `realm-management`, probado contra Keycloak 26.8.2, la cuenta de servicio puede:
  - asignar `PLATFORM_ADMIN` a cualquier usuario, también a sí misma;
  - borrar el OTP de un administrador de plataforma, resetearle la contraseña, cambiarle el email y desactivarlo.

  Quien robara su secreto se haría administrador de plataforma. Solo le estaban vedados `realm-admin` y la suplantación.
- **Consistencia**: Keycloak y la base de datos no comparten transacción.
- **La FK de las membresías**: `clinic_membership.user_id` referencia `user_account`, y `user_isolation` (ADR 0015) solo deja crear la fila propia.
- **Privacidad entre clínicas**: la respuesta no debe revelar si la persona invitada ya usa Dentasoft en otra clínica.
- **Datos personales**: el nombre y el email no deben copiarse en la base de datos ni en los logs (ADR 0012, ADR 0015).
- **Abuso**: una cuenta de administrador robada no debe poder usar Dentasoft para enviar correo masivo.

## Decisión

- **Cliente propio**: `dentasoft-backend` en el realm `dentasoft`.
  - Es confidencial y solo usa la cuenta de servicio (*client credentials*): sin flujos interactivos, sin redirecciones y con `fullScopeAllowed: false`. Nunca se usa el administrador del realm `master`.
  - El secreto es la variable `DENTASOFT_KC_BACKEND_CLIENT_SECRET`. Keycloak la lee al importar el realm y el backend la recibe como `DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET`.
  - El backend pide y renueva el token con `spring-security-oauth2-client` y llama a la API con `RestClient`. La URL sale de `dentasoft.keycloak-admin.base-url`, que por defecto es `dentasoft.urls.auth`.
  - Detalles de Keycloak 26.8 que hacen falta:
    - el scope `kc-admin-api` pone en el token la audiencia de la Admin API; sin él, la API responde 401;
    - con `fullScopeAllowed: false`, los roles se mapean también en el scope del cliente.
- **Permisos mínimos con FGAP v2** (permisos finos de administración, `adminPermissionsEnabled: true`):
  - La cuenta de servicio solo tiene `query-users`, `query-groups` y `manage-organizations`. No tiene `manage-users` ni `view-users`.
  - Una política de tipo *usuario* sobre `service-account-dentasoft-backend` le da `view`, `view-members`, `manage-members` y `manage-membership` sobre un único grupo, `clinic-users`.
    - Tiene que ser de tipo usuario: una de tipo cliente hace que la búsqueda de usuarios devuelva vacío.
    - El grupo tiene un id fijo en el JSON del realm, porque el permiso lo referencia por su UUID.
  - Los usuarios de clínica se crean dentro de `clinic-users`. Los administradores de plataforma nunca están en ese grupo.
  - **Resultado**, comprobado contra 26.8.2, también tras importar el JSON. Para un usuario de plataforma, todo esto da 403:
    - asignar `PLATFORM_ADMIN`, a otros o a sí misma;
    - verlo, resetear su contraseña, borrar su OTP, cambiarle el email, desactivarlo o cerrar su sesión;
    - meterlo en `clinic-users` o en una organización.

    Además, su email no aparece al buscar, y crear un usuario fuera del grupo da 403.
  - `KeycloakRealmFileTests` comprueba esta configuración en el JSON. El trabajo de CI de #136 (comprobación 5) lo comprobará contra un Keycloak real.
- **Orden de una invitación**: Keycloak nunca va dentro de una transacción.
  1. Buscar el usuario por email o crearlo, sin contraseña.
  2. Añadirlo a la organización de la clínica, cuyo nombre y alias son el id de la clínica. La organización se crea al usarla por primera vez.
  3. Una transacción crea su `user_account` si falta (`create_invited_user_account`, V008) y la membresía, o reactiva la inactiva.
  4. Tras el commit, si la persona aún no ha configurado su cuenta, Keycloak le envía el email con las acciones `VERIFY_EMAIL`, `UPDATE_PASSWORD` y `CONFIGURE_TOTP`. Caduca a las 72 h (`dentasoft.keycloak-admin.invitation-lifespan`).

  Qué pasa si falla cada paso:
  - En los pasos 1 a 3, repetir la petición termina el trabajo sin duplicar nada: el usuario se encuentra por email, añadir un miembro dos veces no cambia nada y `UNIQUE (tenant_id, user_id)` evita dos membresías. Un usuario de Keycloak que se quede huérfano (sin membresía) no tiene contraseña ni acceso.
  - Si falla el paso 4, la membresía queda hecha y `POST /api/v1/clinic/users/{id}/invitation` reenvía el email.
- **Aceptación explícita** (V009). La revisión de M02-T05 vio que, si no, el listado revelaba quién usaba ya Dentasoft en otra clínica, y que se añadía a una persona a una clínica sin preguntarle.
  - Una invitación crea una membresía activa pero **pendiente** (`accepted_at` nulo), que no da acceso: el filtro de clínica activa y `GET /api/v1/me` solo cuentan las aceptadas.
  - La persona ve sus invitaciones en `GET /api/v1/me` (`invitations`) y acepta con `POST /api/v1/me/invitations/{id}/acceptance`. Desde la petición siguiente, la clínica es suya.
  - El trigger de V009 impide que la aplicación cree una membresía ya aceptada y que nadie que no sea su usuario la acepte, y solo una vez. Volver a invitar a un antiguo miembro la deja pendiente otra vez.
  - Mientras está pendiente, el listado solo muestra el email que escribió el administrador y `invitationPending: true`, sin el nombre guardado en Keycloak ni si la cuenta ya estaba configurada. El reenvío responde siempre `202`: Keycloak solo envía el email a quien aún no ha configurado su cuenta.
- **Respuesta idéntica**: `202` sin cuerpo, tanto si la persona ya existía en Keycloak como si no.
  - Si el email es de un usuario que la cuenta de servicio no ve (un administrador de plataforma), crearlo da 409 y no se hace nada, con la misma respuesta. Un administrador de plataforma usa otra cuenta para trabajar en una clínica.
  - A quien ya tenía la cuenta configurada no le llega ningún email de Keycloak. El aviso de «te han añadido a la clínica» queda para M07 (#141).
- **`user_account` del invitado**: se mantiene la FK. V008 añade la política `invited_insert` (endurecida en V010), solo `INSERT` para `dentasoft_app`, que exige tres cosas: `status = 'ACTIVE'`; `created_by` y `updated_by` iguales a `app.user_id`; y que ese usuario sea `ADMIN_CLINICA` activo, con la membresía aceptada, de la clínica activa (`app.tenant_id`). La base de datos lo comprueba aunque la aplicación falle.
  - Leer, cambiar o borrar cuentas ajenas sigue siendo imposible.
  - La aplicación crea la cuenta con la función `create_invited_user_account` (`SECURITY INVOKER`, sujeta a la política). La función inserta y, si la fila ya existe, ignora el `unique_violation` dentro de un subbloque. No vale `INSERT … ON CONFLICT DO NOTHING`: si la fila existente no es visible (la cuenta de quien ya trabaja en otra clínica, el caso habitual), PostgreSQL rechaza la inserción en lugar de saltarla.
  - La función no devuelve nada, así que no revela si la cuenta existía.
- **Desactivar**:
  - Vale en la siguiente petición (M02-T04).
  - Tras el commit, el usuario sale de la organización. Si ya no está en ninguna, se cierran sus sesiones de Keycloak. Las demás clínicas se consultan en Keycloak, porque RLS no deja a la aplicación ver las membresías ajenas en otras clínicas.
  - Repetir la desactivación es idempotente y completa lo que falte en Keycloak.
- **Reglas de autorización**, en el servicio (`@PreAuthorize("hasRole('ADMIN_CLINICA')")`):
  - Nadie cambia su propio rol ni se desactiva.
  - Los cambios bloquean las membresías `ADMIN_CLINICA` activas (`SELECT … FOR UPDATE`) y comprueban que quien hace el cambio sigue siendo administrador. Así, una clínica nunca se queda sin administrador.
- **Datos personales**:
  - El nombre y el email solo van a Keycloak.
  - El listado los pide a Keycloak fila a fila (`GET /users/{id}`), con un máximo de 50 por página y sin caché. Si Keycloak no responde, devuelve los datos de la base de datos con `profile: null`.
  - Los logs y la auditoría solo tienen ids. Los cuerpos de error de Keycloak nunca se registran.
- **Límite**: 20 invitaciones y reenvíos al día por clínica (`shared.Quotas`), sobre los buckets de Redis del límite de peticiones. Si se superan, la respuesta es 429 con `Retry-After`.

## Consecuencias

- **Lo que se gana**:
  - Un secreto robado ya no da acceso de plataforma.
  - Una invitación interrumpida se completa repitiéndola.
  - No se revela qué personas trabajan en otras clínicas.
- **Riesgo residual**: con el secreto, se podría resetear la contraseña, borrar el OTP o cambiar el email de **cualquier usuario de clínica**, de todas las clínicas. Es inevitable, porque crear usuarios y enviarles las acciones exige `manage-members` sobre el grupo. Mitigaciones:
  - el secreto solo vive en variables de entorno;
  - `manage-organizations` vale para todo el realm: con el secreto se podrían crear o borrar organizaciones. Son un espejo, y manda la base de datos (ADR 0015). Añadir a una organización a un usuario que la cuenta no ve (un administrador de plataforma) da 403;
  - los eventos de administración de Keycloak se guardan 90 días;
  - queda pendiente alertar sobre los cambios de credenciales hechos por la cuenta de servicio (M13, #142).
- **Otros límites conocidos**, avisos de la revisión que se aceptan:
  - Un administrador de clínica puede saber si un email es de una cuenta que la cuenta de servicio no ve (un administrador de plataforma): la respuesta es el mismo `202`, pero no aparece ninguna invitación en el listado. Solo afecta a las cuentas de plataforma, y lo frena el límite de 20 invitaciones al día.
  - Cada intento cuenta para el límite diario, también los que terminan en 503 o en 409. Una caída corta de Keycloak, con reintentos, puede gastar la cuota del día.
- **Lo que cuesta**:
  - Un cambio del JSON puede romper FGAP sin que se note: por eso lo vigilan el test del fichero y la comprobación 5 de #136.
  - Keycloak no reimporta un realm que ya existe. Los entornos ya creados aplican el cambio a mano, como explica `infra/README.md`.
  - El listado hace una petición a Keycloak por usuario.

## Alternativas descartadas

- **`manage-users` sin FGAP**: deja que el secreto se convierta en acceso de plataforma (ver «Contexto»).
- **Permiso sobre todos los usuarios con denegación explícita para los de plataforma**: la búsqueda deja de funcionar y crear usuarios dentro del grupo da 403.
- **Invitaciones de organización de Keycloak** (`invite-user`): el usuario no existe hasta que acepta, así que la membresía no se podría crear al invitar.
- **Quitar la FK de `clinic_membership.user_id`**: permitiría membresías de usuarios inexistentes.
- **Una función `SECURITY DEFINER` para saber si el usuario tiene otras clínicas**: revela un dato entre clínicas a la aplicación.
- **Keycloak con Testcontainers en los tests**: unos 0,5–1 GB y 15–20 s más por ejecución. Se usa un Keycloak simulado dentro del proceso, y el contrato se comprueba contra uno real (a mano en M02-T05, en la CI con #136).
