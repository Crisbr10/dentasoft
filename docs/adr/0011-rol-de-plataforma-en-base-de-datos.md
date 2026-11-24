# 0011 — El administrador de la plataforma usa su propio rol de base de datos

Fecha: 2026-11-24 · Estado: aceptada

## Contexto

`PLATFORM_ADMIN` crea, lista, suspende y reactiva clínicas (M01-T05). Para eso necesita ver todas las clínicas, pero la tabla `clinic` tiene RLS sobre `id`: la aplicación, como `dentasoft_app`, solo ve la clínica activa (ADR 0002 y 0010).

Cualquier atajo que `dentasoft_app` pudiera activar es inaceptable: `SET ROLE`, una variable `app.platform = on` o una función `SECURITY DEFINER` que pudiera ejecutar. Si así fuera, un fallo en cualquier parte de la aplicación daría acceso a todas las clínicas.

Además, `clinic_settings` es un dato de la clínica y acabará cerca de credenciales (SMTP y WhatsApp, ver `docs/plan/configuracion.md`) que el administrador de la plataforma no debe leer.

## Decisión

- **Rol `dentasoft_platform`**: `LOGIN`, `NOSUPERUSER`, `NOBYPASSRLS`, `NOINHERIT`, sin membresías en ningún sentido y dueño de nada. Lo crea el superusuario fuera de Flyway, porque `dentasoft_owner` no puede crear roles (`infra/postgres/init/02-platform-role.sh` y `docs/operaciones/rol-de-plataforma.md`). `dentasoft_app` nunca recibe este rol.
- **Permisos y políticas en V002, todos nombrando `TO dentasoft_platform`:**
  - en `clinic`: `SELECT`; `INSERT` en las columnas de alta; `UPDATE` solo en `status` y en las columnas de auditoría y versión. Una política por comando (`platform_select`, `platform_insert` y `platform_update`), sin `DELETE`;
  - en `clinic_settings`: solo `INSERT` del id, la clínica y las columnas de auditoría y versión, con la política `platform_insert`. No puede leerla, cambiarla ni dar valores propios: cada ajuste toma el valor por defecto de su columna.
  - Las políticas de `tenant_isolation` no cambian. Como las nuevas solo alcanzan a `dentasoft_platform`, para `dentasoft_app` todo sigue igual.
- **Su propia unidad de persistencia, solo en el módulo `platform`:**
  - un DataSource con un pool de 2 conexiones;
  - un `EntityManagerFactory` que mapea solo `es.crisbr.dentasoft.platform.persistence` (`PlatformClinic` y `PlatformClinicSettings`, ninguna `TenantScopedEntity`);
  - un `JpaTransactionManager`.
  
  Todos son `@Bean(defaultCandidate = false)`: Spring Boot sigue creando los principales y nada recibe estos por tipo. La unidad principal fija sus paquetes con `@EntityScan` (`EntityPackagesConfiguration`), así que tampoco ve las entidades de plataforma.
- **El post-processor de ADR 0010 no cambia.** También pone `TenantAwareJpaDialect` a la factoría de plataforma y comprueba su transaction manager. La unidad de plataforma recibe los mismos ajustes de Hibernate que la principal (`TenancyPersistence.hibernateProperties()`): sin clínica activa, sus sesiones abren con `NO_CLINIC` y el dialecto no fija nada en PostgreSQL. Los servicios de plataforma, además, se niegan a ejecutarse con una clínica activa.
- **Credenciales obligatorias.** Sin `DENTASOFT_PLATFORM_DATASOURCE_USERNAME` y `DENTASOFT_PLATFORM_DATASOURCE_PASSWORD`, la aplicación no arranca. La URL es por defecto la de la aplicación.
- **`clinic_settings` sigue la regla general.** Tiene su propio `id` (UUID v7) y `tenant_id UNIQUE`, y en el módulo `tenancy` la mapea `ClinicSettings`, una `TenantScopedEntity` con su test de aislamiento. Los secretos nunca van en esta tabla: irán cifrados en una tabla aparte (M07-T07) sobre la que `dentasoft_platform` no tendrá ningún permiso.

## Consecuencias

- **El límite lo impone PostgreSQL, no el código.** Aunque el rol de plataforma se viera comprometido, solo alcanza `clinic` y el alta de `clinic_settings`, nunca datos clínicos.
- **Las credenciales del rol viven en el mismo proceso que el resto de la aplicación.** Un fallo que dé ejecución de código en el backend las alcanza. Para limitarlo:
  - `ModularityTests` impide usar `platform.internal` desde otro módulo;
  - `PlatformDatabaseAccessTests` (ArchUnit) impide pedir los beans de plataforma por su nombre fuera del módulo `platform`;
  - `PlatformRoleTests` compara en su totalidad los permisos del rol;
  - `RowLevelSecurityGuardTests` solo admite las políticas de plataforma declaradas, una a una y para ese rol.
- **Hay dos pools y dos transaction managers.** Si un servicio de plataforma olvida `@Transactional("platformTransactionManager")` o pone `@Transactional` a secas, abre una transacción del gestor principal, que no cubre la unidad de plataforma. Sus escrituras fallan, porque no hay transacción de plataforma a la que unirse. Sus lecturas funcionarían, pero con el mismo rol de plataforma, así que no se amplía ningún acceso.
- **La unidad de plataforma no usa Spring Data.** Declarar repositorios para una segunda unidad desactivaría los repositorios automáticos de la principal, así que `PlatformClinics` usa JPA directamente.
- **Cada módulo nuevo con entidades** añade su paquete a `EntityPackagesConfiguration`. Si lo olvida, `PersistenceUnitsTests` falla.
- **Cada entorno crea el rol una vez**, antes de aplicar V002.

## Alternativas descartadas

- `SET ROLE`, una variable de sesión o un flag que `dentasoft_app` pudiera activar: un fallo en cualquier parte de la aplicación abriría todas las clínicas.
- Funciones `SECURITY DEFINER` para listar o crear clínicas: `dentasoft_app` necesitaría `EXECUTE`, que es el mismo agujero.
- `BYPASSRLS` para el rol de plataforma: vería todas las tablas, también las clínicas.
- Un servicio de plataforma en otro proceso, con sus propias credenciales: aísla mejor, pero añade otro desplegable con su CI y su seguridad. Queda como evolución para M13 si el riesgo lo justifica.
- Debilitar el post-processor para admitir un `DataSourceTransactionManager` de plataforma: la regla de ADR 0010 dejaría de ser absoluta.
