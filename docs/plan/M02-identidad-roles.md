# M02 · Identidad, usuarios y roles

**Objetivo**: que el personal entre con Keycloak (con doble factor), trabaje en una o varias clínicas y solo pueda hacer lo que su rol permite en cada una.
**Depende de**: M01
**Paquete backend**: `identity` · **Frontend**: `core/auth`, `features/users`, `features/settings`

## Alcance

- Realm de Keycloak versionado, Spring Security como Resource Server.
- Usuarios, membresías por clínica y roles; profesionales.
- Login en el frontend, selector de clínica y guardias por rol.

## Roles

| Rol | Ámbito | Puede |
| --- | --- | --- |
| `PLATFORM_ADMIN` | Plataforma | Crear y suspender clínicas. No ve datos clínicos |
| `ADMIN_CLINICA` | Clínica | Configuración, usuarios y roles de su clínica, todo lo demás |
| `ODONTOLOGO` | Clínica | Pacientes, odontograma, planes, notas clínicas, presupuestos |
| `HIGIENISTA` | Clínica | Pacientes, odontograma (registro), notas propias |
| `RECEPCION` | Clínica | Pacientes (datos administrativos), agenda, documentos, envío de firmas |
| `FACTURACION` | Clínica | Presupuestos y, más adelante, facturas |

La matriz detallada de permisos por endpoint se mantiene en este fichero a medida que se crean los módulos.

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `user_account` | `id`, `keycloak_user_id`, `email`, `full_name`, `status` (global, sin `tenant_id`) |
| `clinic_membership` | `tenant_id`, `user_id`, `role`, `active` |
| `practitioner` | `tenant_id`, `user_id`, `license_number` (nº de colegiado), `specialty`, `agenda_color` |

## Tareas

### M02-T01 · Realm de Keycloak versionado

- **Estado**: hecho
- **Depende de**: M00-T03
- **Descripción**: realm `dentasoft` con cliente público `dentasoft-web` (PKCE S256), scope `dentasoft-api` con audiencia, organizaciones activadas, OTP obligatorio, protección contra fuerza bruta, política de contraseñas y SMTP a Mailpit; exportado a `infra/keycloak/realm-dentasoft.json` e importado al arrancar. Redirect URIs, orígenes web y SMTP se parametrizan con variables de entorno (ADR 0006).
- **Criterios de aceptación**:
  - [x] `docker compose up` deja el realm listo sin pasos manuales
  - [x] El fichero exportado no contiene contraseñas de usuarios reales
  - [x] Usuarios de prueba (inventados) documentados para local
  - [x] Ningún dominio escrito en el JSON del realm: redirect URIs y orígenes salen de variables

### M02-T02 · Spring Security como Resource Server

- **Estado**: hecho
- **Depende de**: M02-T01, M00-T08
- **Descripción**: cadena de seguridad sin estado que valida el JWT de Keycloak (emisor y audiencia `dentasoft-api`), con CORS por variable y rutas públicas mínimas (health, liveness y readiness). Las rutas de webhooks se abren con el primer webhook (M07-T04), no antes. `/api/v1/clinic/**` sigue cerrada con `denyAll` hasta M02-T04, que fija la clínica activa.
- **Criterios de aceptación**:
  - [x] Token de otra audiencia o caducado → 401
  - [x] Tests con tokens generados en el test, sin Keycloak real
  - [x] El emisor sale de `dentasoft.urls.auth` y el realm; la audiencia `dentasoft-api` se exige; solo RS256 (se rechazan `none` y HS256, también firmado con la clave pública); `exp` obligatorio con 30 s de tolerancia; `sub` debe ser un UUID
  - [x] `jwk-set-uri` explícito: la aplicación arranca aunque Keycloak no responda, y con el JWKS colgado la petición da 401 en segundos (2 s de conexión y 3 s de lectura)
  - [x] `typ: Bearer` obligatorio: un ID token o un refresh token dan 401
  - [x] El límite anónimo por IP se aplica antes de Spring Security y cuenta también los 401: muchas peticiones con un token falso desde la misma IP acaban en 429 sin validar la firma, también en paralelo
  - [x] El 401 lleva `X-Request-Id` y el fallo del JWKS se registra sin datos del token
  - [x] 401 sin token, caducado, de otra audiencia, de otro emisor, firmado con otra clave, con `alg: none` y con HS256; 401 y 403 con `ProblemDetail` que no dice por qué se rechazó el token
  - [x] `realm_access.roles` → `ROLE_PLATFORM_ADMIN`; `/api/v1/platform/**` solo para `PLATFORM_ADMIN`
  - [x] `CurrentActor` registra `USER` con el `sub`; `created_by`/`updated_by` y el `actor_id` de las entradas `PLATFORM` guardan ese `sub`
  - [x] CORS sin credenciales, con los orígenes exactos de la variable y solo los métodos y cabeceras que usa la API
  - [x] Rutas públicas: solo health, liveness y readiness, y `/v3/api-docs/**` y `/swagger-ui/**` mientras `springdoc.api-docs.enabled` esté activo; test de que fuera del perfil local no devuelven la documentación
- **Nota**: el criterio «una clínica suspendida no puede ser la clínica activa» se ha movido a M02-T04, que es la tarea que fija la clínica activa a partir de la petición y valida la membresía.

### M02-T03 · Usuarios y membresías

- **Estado**: hecho
- **Depende de**: M02-T02, M01-T02
- **Descripción**: `user_account` creado en el primer acceso a partir del token, `clinic_membership` con rol por clínica y endpoint `/api/v1/me` con las clínicas y roles del usuario.
- **Criterios de aceptación**:
  - [x] Un usuario puede pertenecer a varias clínicas con roles distintos
  - [x] Un usuario sin membresías activas no puede operar: `/me` no le devuelve ninguna clínica y RLS no le deja leer clínicas ni membresías; el 403 al elegir una clínica es de M02-T04
- **Decisiones** (ADR 0015):
  - `user_account` solo guarda el `sub` como `id`, el estado y las fechas.
  - Cada transacción con usuario fija `app.user_id`. Las políticas `user_isolation`, `own_memberships` y `member_select` solo dejan ver al usuario lo suyo.
  - Un enumerado `ClinicRole` cerrado, con un rol por clínica. No hay endpoint de alta de membresías.
  - `GET /api/v1/me` devuelve las membresías activas del usuario, con el rol y si la clínica está suspendida.
  - Un lote lanzado desde una petición se audita como el usuario que lo lanzó.
- **Nota**: desde M02-T02 el registro de auditoría guarda `USER` con el `sub` del token, y los procesos sin usuario (`ClinicBatch`, listeners con `ClinicTransactions`, M01-T08) siguen registrándose como `SYSTEM`: sin usuario autenticado, `SYSTEM`. Una autenticación que no sea de token se rechaza en lugar de registrarse como `SYSTEM` (`shared.CurrentUser`). Si se permite lanzar un lote desde una petición con usuario (por ejemplo, un `PLATFORM_ADMIN`), aquí se decide si se audita como ese usuario o como `SYSTEM`; en el segundo caso, `ClinicTransactions` tendrá que fijar el actor o limpiar el `SecurityContext`.

### M02-T04 · Filtro de clínica activa

- **Estado**: hecho
- **Depende de**: M02-T03, M01-T03
- **Descripción**: `TenantFilter` que lee `X-Clinic-Id`, comprueba la membresía activa y que la clínica no esté suspendida, fija el `TenantContext` y convierte el rol de clínica en autoridades de Spring.
- **Criterios de aceptación**:
  - [x] Clínica ajena o suspendida → 403
  - [x] Una clínica suspendida no puede ser la clínica activa (403 con ProblemDetail) y hay un test que lo comprueba (criterio movido desde M02-T02)
  - [x] `@PreAuthorize("hasRole('ODONTOLOGO')")` funciona con el rol de la clínica activa
  - [x] Test de que no se puede cambiar de clínica a mitad de petición
- **Decisiones** (ADR 0015):
  - El filtro es `identity.internal.ActiveClinicFilter`. Corre después de Spring Security y del alta del usuario, solo en `/api/v1/clinic/**`.
  - Comprueba en cada petición, sin caché, la membresía activa, la cuenta `ACTIVE` y la clínica no suspendida. Lo hace en una transacción de solo lectura propia, en `dentasoft_app` con `app.user_id`, antes de fijar el `TenantContext`.
  - Sin `X-Clinic-Id`, con la cabecera repetida o con un valor que no es un UUID: 400.
  - Clínica inexistente, ajena o suspendida, membresía inactiva, cuenta desactivada o `PLATFORM_ADMIN` sin membresía: el mismo 403, registrado en el log solo con ids. El limitador por usuario cuenta estos intentos.
  - `ROLE_<rol>` se añade a las autoridades del token solo durante la petición. Los roles de clínica solo valen con `@PreAuthorize`, no en reglas por URL.
  - Ningún endpoint responde de forma asíncrona (`NoAsyncEndpointsTests`).

### M02-T05 · Gestión de usuarios de la clínica

- **Estado**: pendiente
- **Depende de**: M02-T04
- **Descripción**: `ADMIN_CLINICA` invita usuarios (creación en Keycloak con la Admin API, alta en la organización y email de bienvenida), cambia roles y desactiva membresías.
- **Criterios de aceptación**:
  - [ ] Credenciales de la Admin API solo por variables de entorno y con permisos mínimos
  - [ ] Desactivar una membresía impide el acceso a esa clínica de inmediato
  - [ ] Cada cambio queda en `audit_log`
- **Notas** (decisiones en el ADR 0016):
  - **Endpoints**, todos bajo `/api/v1/clinic/users` y solo para `ADMIN_CLINICA`:
    - `GET` lista, con un máximo de 50 por página;
    - `POST /invitations`, que responde `202` sin cuerpo en todos los casos;
    - `POST /{id}/invitation` reenvía la invitación;
    - `PUT /{id}/role` cambia el rol;
    - `POST /{id}/deactivation` desactiva la membresía.
  - **Aceptación**: la invitación queda pendiente hasta que la persona la acepta, con `GET /api/v1/me` (`invitations`) y `POST /api/v1/me/invitations/{id}/acceptance` (V009).
  - **FK de `user_account`**: se mantiene. V008 añade la política `invited_insert` y la función `create_invited_user_account`.
  - **Cuenta de servicio**: `dentasoft-backend`, limitada con FGAP v2 al grupo `clinic-users`. #136 lo comprobará en la CI.
  - **Reglas del servicio**:
    - nadie cambia su propio rol ni se desactiva;
    - los cambios bloquean las membresías de administrador y comprueban que quien hace el cambio sigue siéndolo, así que nunca se queda una clínica sin `ADMIN_CLINICA`.
  - **Pendiente**: una cuenta `DISABLED` todavía lee sus clínicas por SQL (`member_select` no mira `user_account.status`). Se decide cuando exista la forma de desactivar cuentas.

### M02-T06 · Profesionales

- **Estado**: pendiente
- **Depende de**: M02-T05
- **Descripción**: ficha de profesional (número de colegiado, especialidad, color en la agenda) enlazada al usuario.
- **Criterios de aceptación**:
  - [ ] CRUD con test de aislamiento
  - [ ] Un profesional desactivado no aparece para nuevas citas

### M02-T07 · Login y sesión en el frontend

- **Estado**: pendiente
- **Depende de**: M02-T03, M00-T05
- **Descripción**: `angular-auth-oidc-client` con código + PKCE y renovación de token, interceptor que añade `Authorization` y `X-Clinic-Id` solo a la API, selector de clínica tras el login, guardias por rol y menú según permisos.
- **Criterios de aceptación**:
  - [ ] Cerrar sesión cierra también la sesión de Keycloak
  - [ ] Los tokens no se guardan en `localStorage`
  - [ ] Renovación del token con refresh token, sin iframes de Keycloak (`silentRenew` sin iframe y sin `checkSession`), para no abrir `frame-src` en la CSP; si hiciera falta un iframe, se documenta y se añade el origen de Keycloak solo a `frame-src`
  - [ ] Cambiar de clínica recarga los datos de la nueva clínica

### M02-T08 · Pantallas de administración

- **Estado**: pendiente
- **Depende de**: M02-T05, M01-T05
- **Descripción**: pantallas para `PLATFORM_ADMIN` (clínicas) y para `ADMIN_CLINICA` (usuarios, roles, profesionales, gabinetes).
- **Criterios de aceptación**:
  - [ ] Textos con Transloco y formularios validados
  - [ ] Flujo completo: crear clínica → invitar administrador → el administrador invita a un odontólogo

### M02-T09 · Apartado de Configuración

- **Estado**: pendiente
- **Depende de**: M02-T07, M01-T06
- **Descripción**: marco del apartado de Configuración (ver `configuracion.md`): ruta `/configuracion` con menú de secciones filtrado por rol, guardias por sección, patrón común de formulario (guardar, descartar, validación del servidor) y componente para secretos de solo escritura. En el backend, endpoint que devuelve las secciones visibles para el usuario y auditoría de cambios de configuración.
- **Criterios de aceptación**:
  - [ ] Cada rol ve exactamente las secciones de la matriz de `configuracion.md` (test por rol)
  - [ ] Acceder por URL a una sección no permitida → página de «sin acceso» y 403 en la API
  - [ ] Las secciones ya existentes (clínica, gabinetes, usuarios, profesionales) cuelgan de este apartado

### M02-T10 · Preferencias personales

- **Estado**: pendiente
- **Depende de**: M02-T09
- **Descripción**: preferencias de cada usuario (idioma de la interfaz, vista de agenda por defecto, profesional o gabinete por defecto) guardadas en el backend y aplicadas al iniciar sesión.
- **Criterios de aceptación**:
  - [ ] Las preferencias siguen al usuario en cualquier navegador
  - [ ] Valores por defecto de la clínica cuando el usuario no ha elegido
