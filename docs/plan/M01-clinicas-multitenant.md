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

## Roles de base de datos y Row Level Security

- **`dentasoft_owner`** es dueño del esquema y ejecuta las migraciones (`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`).
- **`dentasoft_app`** es el rol con el que se conecta la aplicación (`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`). Es `NOBYPASSRLS`, no es dueño de nada y no puede crear objetos.
- **Permisos por defecto** (`V001`): toda tabla nueva de `dentasoft_owner` en `public` da a `dentasoft_app` `SELECT`, `INSERT` y `UPDATE`. Nunca `DELETE` ni `TRUNCATE`, porque lo clínico se borra de forma lógica. `dentasoft_app` no tiene acceso a `flyway_schema_history`.
- **Política `tenant_isolation`**: compara la columna de clínica con `NULLIF(current_setting('app.tenant_id', true), '')::uuid`, en `USING` y en `WITH CHECK`. Falla cerrado: sin clínica activa, o con el valor vacío tras limpiarlo, no se ve ni se escribe ninguna fila, y no se produce ningún error.
- **`clinic`** es la excepción: su política compara `id`, no `tenant_id`, porque la fila es la clínica misma.
- **`RowLevelSecurityGuardTests`** recorre todas las tablas del esquema y falla si alguna no cumple. Las excepciones a la columna `tenant_id` se declaran en ese test, cada una con su motivo.

### Cómo añadir una tabla con RLS

Dentro de una migración nueva (`V{NNN}__descripcion.sql`):

```sql
CREATE TABLE patient
(
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES clinic (id),
    -- campos de negocio…
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint      NOT NULL DEFAULT 0
);

-- El índice empieza por tenant_id: todas las consultas filtran por clínica.
CREATE INDEX patient_tenant_id_idx ON patient (tenant_id);

-- ENABLE + FORCE ROW LEVEL SECURITY y la política tenant_isolation (USING y WITH CHECK).
SELECT enable_tenant_isolation('patient');
```

- Identificadores `uuid` generados en la aplicación (o columnas `identity`), nunca `serial`: los permisos por defecto no incluyen secuencias.
- `DELETE` solo en tablas no clínicas que necesiten borrado físico, y tabla a tabla: `GRANT DELETE ON treatment_room TO dentasoft_app;`.
- Las funciones nuevas no las puede ejecutar nadie salvo su dueño; concede `EXECUTE` a `dentasoft_app` una a una si hace falta.
- Ni vistas materializadas ni tablas foráneas en `public`: RLS no se aplica a ellas y el test guardián las rechaza.

- No escribas la política a mano: el test guardián compara la expresión exacta y rechaza cualquier otra, además de cualquier segunda política.
- La entidad JPA extiende `TenantScopedEntity` (M01-T02).
- Cada módulo con datos añade su test de aislamiento (M01-T04).

### Migraciones que tocan datos

`FORCE ROW LEVEL SECURITY` también sujeta a RLS a `dentasoft_owner`, el dueño de las tablas. Una migración que haga `UPDATE`, `DELETE` o `INSERT ... SELECT` sin clínica activa **ve 0 filas y no hace nada, sin avisar**. Para cambiar datos de todas las clínicas, recórrelas fijando la clínica en cada vuelta:

```sql
DO
$$
    DECLARE
        clinic_id uuid;
    BEGIN
        -- clinic también tiene RLS: para listar todas las clínicas hay que quitarlo un momento.
        -- ALTER TABLE solo lo puede ejecutar el dueño, y forma parte de la transacción de la migración.
        ALTER TABLE clinic NO FORCE ROW LEVEL SECURITY;
        FOR clinic_id IN SELECT id FROM clinic
            LOOP
                -- true = solo para esta transacción
                PERFORM set_config('app.tenant_id', clinic_id::text, true);
                UPDATE treatment_room SET color = '#1E88E5' WHERE color IS NULL;
            END LOOP;
        ALTER TABLE clinic FORCE ROW LEVEL SECURITY;
        PERFORM set_config('app.tenant_id', '', true);
    END
$$;
```

- `ALTER TABLE clinic` bloquea la tabla (`ACCESS EXCLUSIVE`) hasta que termina la migración, y con ella las lecturas de clínicas y las claves foráneas hacia `clinic`. Mantén estas migraciones cortas o ejecútalas con la aplicación parada.
- El `NO FORCE` de `clinic` solo vale para el dueño y dentro de la transacción de la migración: si algo falla, Flyway la deshace entera.
- Comprueba el resultado en la propia migración (por ejemplo, con un `RAISE EXCEPTION` si quedan filas sin migrar) en vez de confiar en que el `UPDATE` haya encontrado filas.

## Tareas

### M01-T01 · Migración base y roles de base de datos

- **Estado**: pendiente
- **Depende de**: M00-T03
- **Descripción**: migración `V001` con la extensión `vector`, tabla `clinic`, permisos por defecto para `dentasoft_app` y función SQL de ayuda para crear la política `tenant_isolation` en tablas nuevas. Sin `pgcrypto`: `gen_random_uuid()` es nativa desde PostgreSQL 13 y los UUID v7 los genera la aplicación.
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
- **Descripción**: fijar `app.tenant_id` por transacción, no por conexión: al empezar cada transacción, `set_config('app.tenant_id', …, true)` (equivale a `SET LOCAL`). PostgreSQL lo descarta al confirmar o deshacer, así que la conexión vuelve al pool sin clínica aunque falle la petición. Todo acceso a datos de una clínica va dentro de una transacción; fuera de ella no se ve ninguna fila (falla cerrado).
- **Criterios de aceptación**:
  - [ ] Una consulta nativa sin filtro solo devuelve filas de la clínica activa
  - [ ] Sin clínica activa, las tablas con RLS devuelven cero filas
  - [ ] Ninguna conexión vuelve al pool con una clínica fijada: tras cada transacción, también si falla, `app.tenant_id` queda vacío (test con concurrencia)

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

### M01-T09 · Consulta del registro de auditoría

- **Estado**: pendiente
- **Depende de**: M01-T07, M02-T09
- **Descripción**: sección «Seguridad y auditoría» del apartado de Configuración para `ADMIN_CLINICA`: consulta paginada y filtrable del `audit_log` de su clínica (usuario, acción, entidad, fechas) y exportación a CSV.
- **Criterios de aceptación**:
  - [ ] Solo `ADMIN_CLINICA` y solo los registros de su clínica
  - [ ] Consultar el registro también queda registrado
