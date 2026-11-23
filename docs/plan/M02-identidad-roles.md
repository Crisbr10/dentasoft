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

- **Estado**: pendiente
- **Depende de**: M00-T03
- **Descripción**: realm `dentasoft` con cliente público `dentasoft-web` (PKCE S256), scope `dentasoft-api` con audiencia, organizaciones activadas, OTP obligatorio, protección contra fuerza bruta, política de contraseñas y SMTP a Mailpit; exportado a `infra/keycloak/realm-dentasoft.json` e importado al arrancar. Redirect URIs, orígenes web y SMTP se parametrizan con variables de entorno (ADR 0006).
- **Criterios de aceptación**:
  - [ ] `docker compose up` deja el realm listo sin pasos manuales
  - [ ] El fichero exportado no contiene contraseñas de usuarios reales
  - [ ] Usuarios de prueba (inventados) documentados para local
  - [ ] Ningún dominio escrito en el JSON del realm: redirect URIs y orígenes salen de variables

### M02-T02 · Spring Security como Resource Server

- **Estado**: pendiente
- **Depende de**: M02-T01, M00-T08
- **Descripción**: cadena de seguridad sin estado que valida el JWT de Keycloak (emisor y audiencia `dentasoft-api`), con CORS por variable y rutas públicas mínimas (health, webhooks firmados).
- **Criterios de aceptación**:
  - [ ] Token de otra audiencia o caducado → 401
  - [ ] Tests con tokens generados en el test, sin Keycloak real

### M02-T03 · Usuarios y membresías

- **Estado**: pendiente
- **Depende de**: M02-T02, M01-T02
- **Descripción**: `user_account` creado en el primer acceso a partir del token, `clinic_membership` con rol por clínica y endpoint `/api/v1/me` con las clínicas y roles del usuario.
- **Criterios de aceptación**:
  - [ ] Un usuario puede pertenecer a varias clínicas con roles distintos
  - [ ] Un usuario sin membresías activas no puede operar

### M02-T04 · Filtro de clínica activa

- **Estado**: pendiente
- **Depende de**: M02-T03, M01-T03
- **Descripción**: `TenantFilter` que lee `X-Clinic-Id`, comprueba la membresía activa y que la clínica no esté suspendida, fija el `TenantContext` y convierte el rol de clínica en autoridades de Spring.
- **Criterios de aceptación**:
  - [ ] Clínica ajena o suspendida → 403
  - [ ] `@PreAuthorize("hasRole('ODONTOLOGO')")` funciona con el rol de la clínica activa
  - [ ] Test de que no se puede cambiar de clínica a mitad de petición

### M02-T05 · Gestión de usuarios de la clínica

- **Estado**: pendiente
- **Depende de**: M02-T04
- **Descripción**: `ADMIN_CLINICA` invita usuarios (creación en Keycloak con la Admin API, alta en la organización y email de bienvenida), cambia roles y desactiva membresías.
- **Criterios de aceptación**:
  - [ ] Credenciales de la Admin API solo por variables de entorno y con permisos mínimos
  - [ ] Desactivar una membresía impide el acceso a esa clínica de inmediato
  - [ ] Cada cambio queda en `audit_log`

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
