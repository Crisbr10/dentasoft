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
| `clinic_settings` | `id`, `tenant_id` (único: una fila por clínica), `default_appointment_minutes`, `contact_email`, `contact_phone` (M01-T05); el logo llega con la subida segura de ficheros (M05-T06). Nunca secretos: las credenciales de los canales van cifradas en su propia tabla (M07-T07) |
| `clinic_hours_of_operation` | `tenant_id`, `day_of_week` (1 = lunes … 7 = domingo, ISO 8601), `opening_time`, `closing_time` (tipo `time`, hora de reloj de la clínica en su zona horaria). Varios tramos por día, sin solaparse (M01-T06) |
| `location` | `tenant_id`, `name`, `color`, `status` (activo, inactivo). Gabinete, alineado con el recurso FHIR `Location`; el nombre es único entre los activos de la clínica (M01-T06) |
| `audit_log` | `tenant_id`, `occurred_at`, `actor_type`, `actor_id`, `action`, `entity_type`, `entity_id`, `outcome`, `request_id`, `details` (tipo cerrado, sin datos personales ni clínicos). Sin IP. Solo se añade (ADR 0012) |

## Roles de base de datos y Row Level Security

- **`dentasoft_owner`** es dueño del esquema y ejecuta las migraciones (`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`).
- **`dentasoft_app`** es el rol con el que se conecta la aplicación (`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`). Es `NOBYPASSRLS`, no es dueño de nada y no puede crear objetos.
- **`dentasoft_platform`** es el rol del módulo `platform`, la administración de clínicas de `PLATFORM_ADMIN` (`DENTASOFT_PLATFORM_DATASOURCE_USERNAME`, `DENTASOFT_PLATFORM_DATASOURCE_PASSWORD`; ADR 0011). Es `NOBYPASSRLS`, ningún rol es miembro suyo y solo tiene, en `V002`, `SELECT`, el alta y el cambio de estado en `clinic` y el alta de `clinic_settings`, con políticas `TO dentasoft_platform`; en `V006`, el `UPDATE` de `legal_name` y `tax_id`, para corregir los datos fiscales (M01-T11). Lo crea el superusuario fuera de Flyway ([`docs/operaciones/rol-de-plataforma.md`](../operaciones/rol-de-plataforma.md)). Ninguna tabla nueva le da permisos: si un módulo los necesita, se conceden en su migración y se añaden a `PlatformRoleTests`.
- **Permisos por defecto** (`V001`): toda tabla nueva de `dentasoft_owner` en `public` da a `dentasoft_app` `SELECT`, `INSERT` y `UPDATE`. Nunca `DELETE` ni `TRUNCATE`, porque lo clínico se borra de forma lógica. La única tabla con `DELETE` es `clinic_hours_of_operation` (`V003`): el horario se sustituye entero y no es un dato clínico.
- **`clinic` para la aplicación** (`V003`): solo `SELECT` y `UPDATE` de `name`, `timezone`, `updated_at`, `updated_by` y `version`. Ni `INSERT` ni el resto de columnas: la clínica no puede reactivarse, cambiar su `keycloak_org_id` ni sus datos fiscales, ni crear clínicas. `dentasoft_app` no tiene acceso a `flyway_schema_history`.
- **Política `tenant_isolation`**: compara la columna de clínica con `NULLIF(current_setting('app.tenant_id', true), '')::uuid`, en `USING` y en `WITH CHECK`. Falla cerrado: sin clínica activa, o con el valor vacío tras limpiarlo, las lecturas, los `UPDATE` y los `DELETE` no ven ninguna fila, sin error; un `INSERT` (o un `UPDATE` que mueva una fila a otra clínica) incumple el `WITH CHECK` y falla con `42501`. Un valor que no es UUID da error `22P02` en cualquier consulta.
- **`clinic`** es la excepción: su política compara `id`, no `tenant_id`, porque la fila es la clínica misma. Una clínica nunca puede tener el UUID nulo (`clinic_id_not_nil`, V002), que es el centinela «sin clínica».
- **`RowLevelSecurityGuardTests`** recorre todas las tablas del esquema y falla si alguna no cumple. Las excepciones a la columna `tenant_id` y las políticas permitidas además de `tenant_isolation` (solo las de `dentasoft_platform`) se declaran en ese test, cada una con su motivo.

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
- `DELETE` solo en tablas no clínicas que necesiten borrado físico, tabla a tabla y con un comentario que lo justifique, como `GRANT DELETE ON clinic_hours_of_operation TO dentasoft_app;` (V003). `TenancyMigrationTests` comprueba qué tablas lo tienen: la tabla nueva se añade a ese test en el mismo pull request.
- Las funciones nuevas no las puede ejecutar nadie salvo su dueño; concede `EXECUTE` a `dentasoft_app` una a una si hace falta.
- Ni vistas materializadas ni tablas foráneas en `public`: RLS no se aplica a ellas y el test guardián las rechaza.

- No escribas la política a mano: el test guardián compara la expresión exacta y rechaza cualquier otra, además de cualquier segunda política.
- La entidad JPA extiende `TenantScopedEntity` (M01-T02).
- La primera entidad de un módulo añade el paquete del módulo a `EntityPackagesConfiguration` (`shared.persistence`): la unidad de persistencia principal no escanea toda la aplicación (ADR 0011), y `PersistenceUnitsTests` falla si una entidad no está en ninguna unidad.
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

## Clínica activa en la aplicación

### `TenantContext`

La clínica activa vive en un `ScopedValue`. Solo existe dentro del bloque y desaparece al salir de él, aunque falle. No hay `set()`, así que no puede quedarse en el hilo ni pasar a otra petición.

```java
TenantContext.runAs(clinicId, () -> appointments.confirm(appointmentId));
Quote quote = TenantContext.callAs(clinicId, () -> quotes.find(quoteId));
UUID clinicId = TenantContext.current(); // NoActiveClinicException si no hay clínica activa
```

Hibernate fija la clínica de la sesión al abrirla, al empezar el `@Transactional` exterior. Por eso hay dos reglas, y las dos lanzan `IllegalStateException` si se incumplen:

- **No se cambia de clínica dentro de otra.** Anidar la misma clínica está permitido.
- **No se fija la clínica dentro de una transacción ya empezada.** Primero `runAs` y después la transacción, nunca al revés. Un job recorre las clínicas sin una transacción que envuelva el bucle: cada vuelta, `runAs` y dentro un servicio `@Transactional`.

`runAs` solo detecta las transacciones de Spring. Por eso:

- **`spring.jpa.open-in-view` sigue en `false`.** Con él, la sesión se abriría al empezar la petición, antes de `runAs`, y se quedaría con `NO_CLINIC` toda la petición.
- **Un `EntityManager` abierto a mano conserva la clínica con la que se abrió.** Si se escribe con él con otra clínica activa, `TenantWriteGuard` lo rechaza.

Hasta que exista la validación de la membresía (M02), la clínica no se lee de ninguna cabecera, ruta ni cuerpo de la petición.

### Sin clínica activa: el centinela `NO_CLINIC`

`ActiveClinicResolver` da a la sesión el UUID nulo `00000000-0000-0000-0000-000000000000` cuando no hay clínica activa. Ese valor nunca es una clínica: los id de clínica son UUID v7 y `runAs` lo rechaza. Con él:

- **Las lecturas no devuelven nada.** Toda consulta JPQL o de Spring Data, `find()` y `UPDATE`/`DELETE` en JPQL sobre una `TenantScopedEntity` filtran por `tenant_id = 00000000-…`: devuelven vacío y no cambian ninguna fila. No dan error: un `runAs` olvidado parece «no hay datos».
- **Las escrituras no llegan a hacerse.**
  - Guardar una entidad nueva lanza `NoActiveClinicException` (`TenantWriteGuard`, un listener `PRE_INSERT`/`PRE_UPDATE`/`PRE_DELETE` de Hibernate). También falla si la sesión se abrió para otra clínica.
  - Una entidad que ya existe no se puede cargar, porque la lectura está filtrada. Guardarla separada (`save` de una entidad *detached*) falla con `ObjectOptimisticLockingFailureException`, y `delete`/`deleteById` no hacen nada.
  - El listener es la última barrera para los `UPDATE` y `DELETE` de entidades.
  - Las sentencias JPQL masivas no disparan el listener (ver más abajo).
- **Lo que no es de una clínica sigue accesible**: la tabla `clinic`, la administración de plataforma y, si se añade, el registro de eventos de Spring Modulith.

Ninguna clínica es «root» (`isRoot` siempre devuelve `false`), porque Hibernate quitaría el filtro. Por qué un centinela y no un resolver que lance excepción: lo explica el [comentario de la decisión en el issue #10](https://github.com/Crisbr10/dentasoft/issues/10#issuecomment-7125108865). En resumen, la excepción rompía el arranque de Spring Data, la plataforma (M01-T05), los jobs y el registro de eventos.

### Entidades

- **`BaseEntity`** (`shared`):
  - id UUID v7 (`@UuidGenerator(style = VERSION_7)`);
  - `@Version Long version`: un objeto, no un `long`, para que Spring Data distinga las entidades nuevas;
  - `created_at`, `created_by`, `updated_at` y `updated_by`, con `Instant` y `UUID`. `created_by` y `updated_by` quedan vacíos hasta M02, que los rellenará con el `sub` de Keycloak (`AuditingConfiguration`).
- **`TenantScopedEntity`** (`tenancy`): extiende `BaseEntity` y añade `tenant_id` con `@TenantId`. Al guardar una entidad, Hibernate rellena `tenant_id` con la clínica activa. Además añade `tenant_id = <clínica activa>` a cada consulta JPQL, consulta de Spring Data, `find()` y `UPDATE`/`DELETE` de entidades o en JPQL. No hay filtro que escribir a mano.
- **`tenant_id` no se cambia desde una entidad**: no tiene setter y la columna no es actualizable. Si aun así se cambia el valor en memoria, Hibernate lo ignora al guardar y no da error. Al guardar una entidad nueva, un valor distinto de la clínica activa falla (`PropertyValueException`).

### Lo que `@TenantId` no protege

Ahí solo protege el Row Level Security (ver «La clínica en PostgreSQL»):

- las consultas nativas y `JdbcClient`;
- el SQL propio (`@SQLDelete`, `@SQLUpdate`, `@Formula`);
- un `INSERT` en JPQL. Hibernate no rellena `tenant_id`, y si la sentencia lo incluye, lo envía tal cual. Tampoco pasa por `TenantWriteGuard`;
- un `UPDATE` en JPQL que asigna `tenantId`. Hibernate filtra la fila por la clínica activa, pero sí envía el cambio de `tenant_id` y no dispara `TenantWriteGuard`. Hoy lo para el `WITH CHECK` de la política `tenant_isolation`.

No escribas sentencias JPQL que asignen `tenantId`.

### La clínica en PostgreSQL

Al empezar cada transacción de Spring, `TenantAwareJpaDialect` ejecuta `SELECT set_config('app.tenant_id', ?, true)` con la clínica de la sesión de Hibernate (ADR 0010). PostgreSQL lo descarta al confirmar o deshacer, así que ninguna conexión vuelve al pool con una clínica.

- **Leer o escribir datos de una clínica exige una transacción**: `@Transactional` en el servicio o `TransactionTemplate`. Fuera de ella, las consultas nativas y `JdbcClient` no ven ninguna fila, aunque haya una clínica activa. Los métodos de los repositorios de Spring Data abren su propia transacción.
- `JdbcClient` dentro de la transacción usa la misma conexión y la misma clínica.
- `REQUIRES_NEW` abre otra conexión, que recibe la misma clínica. Las transacciones de solo lectura también la reciben.
- Si la sesión se abrió para otra clínica que la activa, la transacción no empieza (`IllegalStateException`).
- `REQUIRES_NEW` es la forma de abrir una transacción aparte. `NESTED` no está disponible con JPA: `JpaTransactionManager` la rechaza con `NestedTransactionNotSupportedException`.
- **La aplicación no arranca con un transaction manager que no pase la clínica**: `TenantAwareJpaDialectPostProcessor` rechaza todo `PlatformTransactionManager` que no sea un `JpaTransactionManager` con `TenantAwareJpaDialect`, por ejemplo uno de JDBC. Si el propio post-processor deja de registrarse, la aplicación arranca pero no ve ni escribe datos de clínicas, y lo detectan los tests (`TenantAwareJpaDialectPostProcessorTests`, `ConnectionRowLevelSecurityTests`).

### Hilos y procesos asíncronos

La clínica no se hereda ni en las tareas enviadas a un executor, ni en hilos nuevos, ni en métodos `@Async`, ni en listeners `@ApplicationModuleListener`. La única excepción son las subtareas de `StructuredTaskScope`, que heredan los `ScopedValue`, pero esa API sigue en vista previa en Java 25 y no se usa.

`@EnableAsync` (`SharedConfiguration`) hace que `@Async` y `@ApplicationModuleListener` se ejecuten en su propio hilo virtual. Sin él, se ejecutarían en el hilo de quien los llama y con su clínica.

- **Un `@Async` sin `@Transactional`** empieza sin transacción. Fija su clínica con `runAs` y dentro llama a un servicio `@Transactional`.
- **Un `@ApplicationModuleListener` no puede trabajar con datos de una clínica.** Abre su transacción (`REQUIRES_NEW`) antes de ejecutar el método, así que dentro ya no se puede fijar la clínica: `runAs` lo rechaza. `TenantContextPropagationTests` lo comprueba.
- **Un listener que trabaja con datos de una clínica** se declara con `@Async` y `@TransactionalEventListener`, sin `@Transactional`:
  - El evento lleva el id de la clínica.
  - El listener llama a `ClinicTransactions.run(event.clinicId(), …)`, que fija la clínica y abre dentro su propia transacción (ejemplo en `ClinicListenerProbe` y `ClinicTransactionsTests`).
  - **Si el listener falla, el evento se pierde**: su trabajo se deshace y no hay reintentos. Para tenerlos hay que activar el registro de publicaciones de eventos de Spring Modulith, que se hará en M06-T02 (ADR 0013).

### Procesos sin usuario y tareas programadas

Un proceso sin usuario que recorre las clínicas (M01-T08, ADR 0013) usa `ClinicBatch.forEachActiveClinic(job, tarea)`:

- Las clínicas salen de `ActiveClinicIds`, que implementa `platform`: solo ids y solo de clínicas activas. Las suspendidas no se procesan.
- Cada clínica se ejecuta con su clínica activa y en su propia transacción (`ClinicTransactions`). Nunca hay una transacción que abarque varias: `ClinicBatch` se rechaza dentro de una transacción o con una clínica activa.
- Si una clínica falla con una `RuntimeException`, se deshace su trabajo y se registra con su id y la clase de la excepción, sin el mensaje. El proceso sigue con la siguiente y al final escribe un resumen con las que fallaron. Un `Error` detiene el proceso, también si llega envuelto en una `RuntimeException` (la causa de un `CompletionException`, o una `TransactionSystemException` de un rollback fallido).
- `ClinicTransactions` y `ClinicBatch` también se rechazan con la sincronización de transacciones activa sin transacción real (`SUPPORTS`): la sesión de Hibernate se compartiría entre clínicas.
- Sus cambios se auditan con el actor `SYSTEM`.

Para que una tarea programada se ejecute una sola vez con varias réplicas, se añade `@SchedulerLock(name = "…")` a su método `@Scheduled`. ShedLock guarda el bloqueo en `scheduler.shedlock` con la hora de la base de datos. Por defecto, el bloqueo dura como máximo 10 minutos (`lockAtMostFor`, se sube en tareas más largas) y como mínimo 30 segundos (`lockAtLeastFor`): los disparadores de dos réplicas nunca saltan en el mismo instante, y sin ese mínimo una tarea corta ya habría terminado y liberado el bloqueo cuando salta el de la otra réplica, que la ejecutaría otra vez. Una tarea que se ejecuta cada 30 segundos o más a menudo fija un `lockAtLeastFor` claramente menor que su intervalo, por ejemplo la mitad: si el bloqueo vence justo cuando salta el siguiente disparo, unas veces se ejecuta y otras no. Y si una tarea baja `lockAtMostFor` por debajo de 30 segundos, baja también `lockAtLeastFor`: ShedLock rechaza un mínimo mayor que el máximo, y no al arrancar sino cada vez que la tarea intenta coger el bloqueo, así que la tarea no llegaría a ejecutarse nunca.

### Tests

- **Tablas solo de test**: en `src/test/resources/db/testmigration`, numeradas desde `V900` para que se apliquen después de las reales, y con RLS como cualquier otra (el test guardián también las revisa). Ejemplo: `V900__tenancy_test_record.sql`.
- **Los tests pasan por el camino real**: `TenantContext.runAs` y una transacción. No fijan `app.tenant_id` a mano. La única excepción son los dos tests del filtro de Hibernate de `TenantScopedEntityTests`, que simulan un fallo del RLS con `simulateRlsLetsClinicThrough`. Preparar y limpiar datos con conexiones directas fuera de la aplicación (`TenancyTestDatabase`) sí puede fijarlo.

## Tareas

### M01-T01 · Migración base y roles de base de datos

- **Estado**: hecho
- **Depende de**: M00-T03
- **Descripción**: migración `V001` con la extensión `vector`, tabla `clinic`, permisos por defecto para `dentasoft_app` y función SQL de ayuda para crear la política `tenant_isolation` en tablas nuevas. Sin `pgcrypto`: `gen_random_uuid()` es nativa desde PostgreSQL 13 y los UUID v7 los genera la aplicación.
- **Criterios de aceptación**:
  - [x] Flyway migra con `dentasoft_owner` y la app conecta con `dentasoft_app`
  - [x] `dentasoft_app` tiene `NOBYPASSRLS` y no es propietario de ninguna tabla
  - [x] Documentado en el módulo cómo añadir una tabla con RLS

### M01-T02 · TenantContext y entidad base

- **Estado**: hecho
- **Depende de**: M01-T01
- **Descripción**: `TenantContext` (limpiado siempre en `finally`), `TenantScopedEntity` con `@TenantId`, `CurrentTenantIdentifierResolver`, entidad base con UUID v7, auditoría (`created_at/by`, `updated_at/by`) y `@Version`.
- **Criterios de aceptación**:
  - [x] Insertar sin clínica activa falla
  - [x] Las consultas JPA filtran por la clínica activa sin escribir el filtro a mano
  - [x] Tests unitarios del contexto en hilos virtuales

### M01-T03 · Row Level Security en la conexión

- **Estado**: hecho
- **Depende de**: M01-T02
- **Descripción**: fijar `app.tenant_id` por transacción, no por conexión: al empezar cada transacción, `set_config('app.tenant_id', …, true)` (equivale a `SET LOCAL`). PostgreSQL lo descarta al confirmar o deshacer, así que la conexión vuelve al pool sin clínica aunque falle la petición. Todo acceso a datos de una clínica va dentro de una transacción; fuera de ella no se ve ninguna fila (falla cerrado).
- **Criterios de aceptación**:
  - [x] Una consulta nativa sin filtro solo devuelve filas de la clínica activa
  - [x] Sin clínica activa, las tablas con RLS devuelven cero filas
  - [x] Ninguna conexión vuelve al pool con una clínica fijada: tras cada transacción, también si falla, `app.tenant_id` queda vacío (test con concurrencia)

### M01-T04 · Test de aislamiento reutilizable

- **Estado**: hecho
- **Depende de**: M01-T03
- **Descripción**: clase base de test con Testcontainers que crea dos clínicas y comprueba lectura, modificación y borrado cruzados, para reutilizarla en cada módulo.
- **Criterios de aceptación**:
  - [x] Plantilla documentada y usada con una tabla de ejemplo
  - [x] Falla si se quita la política RLS o el `@TenantId`

### M01-T05 · Alta y gestión de clínicas (administración de plataforma)

- **Estado**: hecho
- **Depende de**: M01-T02
- **Descripción**: API para el rol `PLATFORM_ADMIN`: crear, listar, suspender y reactivar clínicas, con su `clinic_settings` inicial.
- **Criterios de aceptación**:
  - [x] Solo `PLATFORM_ADMIN` puede usar estos endpoints. El servicio exige el rol con `@PreAuthorize`, y `/api/v1/platform/**` deniega todas las peticiones hasta M02-T02 (ADR 0011).
  - [x] Una clínica suspendida no permite iniciar sesión en ella (comprobado en M02). Esta tarea guarda el estado `SUSPENDED`; el rechazo del acceso es de M02-T04.
  - [x] Evento `ClinicCreated` (antes «`ClinicaCreada`», en inglés como el resto del código) publicado para que otros módulos creen sus datos iniciales

### M01-T06 · Configuración de la clínica y gabinetes

- **Estado**: hecho
- **Depende de**: M01-T05
- **Descripción**: API para que `ADMIN_CLINICA` edite datos, horario y zona horaria de la clínica, y gestione gabinetes. Las pantallas son M01-T10, porque sin inicio de sesión (M02-T02) no pueden llamar a la API.
- **Criterios de aceptación**:
  - [x] CRUD de gabinetes con validación y test de aislamiento
  - [x] Zona horaria por defecto `Europe/Madrid`

### M01-T07 · Auditoría transversal

- **Estado**: hecho
- **Depende de**: M01-T02
- **Descripción**: tabla `audit_log` (solo `INSERT` para la app) y servicio `AuditService` para registrar accesos y cambios a entidades sensibles, con anotación o listener reutilizable.
- **Criterios de aceptación**:
  - [x] Ningún dato clínico ni personal en `details`
  - [x] La app no puede modificar ni borrar registros de auditoría (test)

### M01-T08 · Procesos sin usuario y tareas programadas

- **Estado**: hecho
- **Depende de**: M01-T03
- **Descripción**: utilidad para ejecutar un proceso clínica a clínica con su `TenantContext`, y ShedLock sobre PostgreSQL para que una tarea programada se ejecute una sola vez aunque haya varias réplicas.
- **Criterios de aceptación**:
  - [x] Test con dos instancias: la tarea se ejecuta una vez
  - [x] Un fallo en una clínica no detiene el proceso para las demás

### M01-T09 · Consulta del registro de auditoría

- **Estado**: pendiente
- **Depende de**: M01-T07, M02-T09
- **Descripción**: sección «Seguridad y auditoría» del apartado de Configuración para `ADMIN_CLINICA`: consulta paginada y filtrable del `audit_log` de su clínica (usuario, acción, entidad, fechas) y exportación a CSV.
- **Criterios de aceptación**:
  - [ ] Solo `ADMIN_CLINICA` y solo los registros de su clínica
  - [ ] Consultar el registro también queda registrado

### M01-T10 · Pantallas de configuración de la clínica y gabinetes

- **Estado**: pendiente
- **Depende de**: M01-T06, M02-T02, M02-T09
- **Descripción**: pantallas de la sección «Clínica» del apartado de Configuración (ver `configuracion.md`, marco de M02-T09) sobre la API de M01-T06, con el cliente generado (`pnpm api:gen`): datos y zona horaria, ajustes, horario semanal con varios tramos por día y gestión de gabinetes (alta, edición, desactivación y reactivación). El logo no entra: lo añade M05-T06.
- **Criterios de aceptación**:
  - [ ] Textos con Transloco y formularios validados; los errores del servidor (validación, nombre de gabinete repetido, conflicto de versión) se muestran en el formulario
  - [ ] El nombre y los datos fiscales se ven; solo el nombre se edita (los fiscales los cambia la plataforma, M01-T11)

### M01-T11 · Corrección de los datos fiscales por la plataforma

- **Estado**: hecho
- **Depende de**: M01-T05
- **Descripción**: la clínica no puede cambiar su `legal_name` ni su `tax_id` (M01-T06). Si hay que corregirlos, lo hace `PLATFORM_ADMIN` desde la administración de clínicas, con su propio permiso por columna para `dentasoft_platform` en una migración nueva.
- **Criterios de aceptación**:
  - [x] Solo `PLATFORM_ADMIN` puede cambiar `legal_name` y `tax_id`, y `PlatformRoleTests` comprueba el nuevo permiso
  - [x] `dentasoft_app` sigue sin poder cambiarlos (test de permisos)
