# M01 · Clínicas y multi-tenant

**Objetivo**: que cada dato pertenezca a una clínica y que sea imposible leer o modificar datos de otra, en la aplicación y en la base de datos.
**Depende de**: M00
**Paquete backend**: `tenancy`, `shared` · **Frontend**: `features/admin`
**ADR**: [0002](../adr/0002-multi-clinica-rls.md)

## Alcance

- Clínicas, configuración y gabinetes.
- `TenantContext`, entidad base y Row Level Security.
- Auditoría transversal y utilidades para procesos sin usuario.

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `clinic` | `id`, `name`, `legal_name`, `tax_id`, `timezone`, `locale`, `status` (activa, suspendida), `keycloak_org_id` |
| `clinic_settings` | `tenant_id`, horario de apertura, duración de cita por defecto, datos de contacto, logo |
| `treatment_room` | `tenant_id`, `name`, `color`, `active` (gabinete) |
| `audit_log` | `tenant_id`, `occurred_at`, `actor_id`, `action`, `entity`, `entity_id`, `ip`, `details` (sin datos clínicos) |

## Tareas

### M01-T01 · Migración base y roles de base de datos

- **Estado**: pendiente
- **Depende de**: M00-T03
- **Descripción**: migración `V001` con extensiones (`pgcrypto`, `vector`), tabla `clinic`, permisos por defecto para `dentasoft_app` y función SQL de ayuda para crear la política `tenant_isolation` en tablas nuevas.
- **Criterios de aceptación**:
  - [ ] Flyway migra con `dentasoft_owner` y la app conecta con `dentasoft_app`
  - [ ] `dentasoft_app` tiene `NOBYPASSRLS` y no es propietario de ninguna tabla
  - [ ] Documentado en el módulo cómo añadir una tabla con RLS

### M01-T02 · TenantContext y entidad base

- **Estado**: pendiente
- **Depende de**: M01-T01
- **Descripción**: `TenantContext` (limpiado siempre en `finally`), `TenantScopedEntity` con `@TenantId`, `CurrentTenantIdentifierResolver`, entidad base con UUID v7, auditoría (`created_at/by`, `updated_at/by`) y `@Version`.
- **Criterios de aceptación**:
  - [ ] Insertar sin clínica activa falla
  - [ ] Las consultas JPA filtran por la clínica activa sin escribir el filtro a mano
  - [ ] Tests unitarios del contexto en hilos virtuales

### M01-T03 · Row Level Security en la conexión

- **Estado**: pendiente
- **Depende de**: M01-T02
- **Descripción**: envolver el `DataSource` para fijar `app.tenant_id` con `set_config` al obtener la conexión y limpiarlo al devolverla al pool.
- **Criterios de aceptación**:
  - [ ] Una consulta nativa sin filtro solo devuelve filas de la clínica activa
  - [ ] Sin clínica activa, las tablas con RLS devuelven cero filas
  - [ ] Ninguna conexión vuelve al pool con la clínica de otra petición (test con concurrencia)

### M01-T04 · Test de aislamiento reutilizable

- **Estado**: pendiente
- **Depende de**: M01-T03
- **Descripción**: clase base de test con Testcontainers que crea dos clínicas y comprueba lectura, modificación y borrado cruzados, para reutilizarla en cada módulo.
- **Criterios de aceptación**:
  - [ ] Plantilla documentada y usada con una tabla de ejemplo
  - [ ] Falla si se quita la política RLS o el `@TenantId`

### M01-T05 · Alta y gestión de clínicas (administración de plataforma)

- **Estado**: pendiente
- **Depende de**: M01-T02
- **Descripción**: API para el rol `PLATFORM_ADMIN`: crear, listar, suspender y reactivar clínicas, con su `clinic_settings` inicial.
- **Criterios de aceptación**:
  - [ ] Solo `PLATFORM_ADMIN` puede usar estos endpoints
  - [ ] Una clínica suspendida no permite iniciar sesión en ella (comprobado en M02)
  - [ ] Evento `ClinicaCreada` publicado para que otros módulos creen sus datos iniciales

### M01-T06 · Configuración de la clínica y gabinetes

- **Estado**: pendiente
- **Depende de**: M01-T05
- **Descripción**: API y pantallas para que `ADMIN_CLINICA` edite datos, horario y zona horaria de la clínica, y gestione gabinetes.
- **Criterios de aceptación**:
  - [ ] CRUD de gabinetes con validación y test de aislamiento
  - [ ] Zona horaria por defecto `Europe/Madrid`

### M01-T07 · Auditoría transversal

- **Estado**: pendiente
- **Depende de**: M01-T02
- **Descripción**: tabla `audit_log` (solo `INSERT` para la app) y servicio `AuditService` para registrar accesos y cambios a entidades sensibles, con anotación o listener reutilizable.
- **Criterios de aceptación**:
  - [ ] Ningún dato clínico ni personal en `details`
  - [ ] La app no puede modificar ni borrar registros de auditoría (test)

### M01-T08 · Procesos sin usuario y tareas programadas

- **Estado**: pendiente
- **Depende de**: M01-T03
- **Descripción**: utilidad para ejecutar un proceso clínica a clínica con su `TenantContext`, y ShedLock sobre PostgreSQL para que una tarea programada se ejecute una sola vez aunque haya varias réplicas.
- **Criterios de aceptación**:
  - [ ] Test con dos instancias: la tarea se ejecuta una vez
  - [ ] Un fallo en una clínica no detiene el proceso para las demás
