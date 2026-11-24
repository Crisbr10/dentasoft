# 0010 — La clínica llega a PostgreSQL al empezar cada transacción

Fecha: 2026-11-24 · Estado: aceptada

## Contexto

La política `tenant_isolation` de ADR 0002 compara `tenant_id` con `app.tenant_id`. Alguien tiene que fijar ese valor en la conexión, y la conexión vuelve después a un pool que comparten todas las peticiones y clínicas. Si se queda con una clínica, la siguiente petición vería sus datos.

Hibernate ya conoce la clínica de cada sesión (ADR 0009), y Spring expone la conexión de la sesión a `JdbcClient` mientras dura la transacción.

## Decisión

- **`app.tenant_id` se fija por transacción, nunca por conexión ni por sesión.**
  - Justo después de empezar la transacción, `TenantAwareJpaDialect`, un `HibernateJpaDialect` propio, ejecuta `SELECT set_config('app.tenant_id', ?, true)` sobre la conexión de la sesión.
  - Siempre con parámetro: `SET LOCAL` no los admite.
  - Ni `connectionInitSql` en Hikari ni `SET` de sesión.
- **El valor es la clínica de la sesión de Hibernate**, que tiene que coincidir con la de `TenantContext`. Si no coincide, la transacción no empieza. Sin clínica (`NO_CLINIC`) no se fija nada.
- **Leer o escribir datos de una clínica exige una transacción de Spring** (`@Transactional` o `TransactionTemplate`). Fuera de ella, las tablas con RLS no devuelven filas, aunque haya una clínica activa.
- **El dialecto se instala en el `EntityManagerFactory`** con un `BeanPostProcessor` (`TenantAwareJpaDialectPostProcessor`). El transaction manager copia el dialecto de la factoría al inicializarse, así que ponerlo solo en él no sirve. El mismo post-processor hace fallar el arranque con cualquier `PlatformTransactionManager` que no sea un `JpaTransactionManager` con este dialecto, por ejemplo uno de JDBC o uno de JTA.

## Consecuencias

- PostgreSQL descarta el valor al confirmar o deshacer, así que una conexión nunca vuelve al pool con una clínica, aunque la petición falle.
- `JdbcClient` y `JdbcTemplate` dentro de la transacción usan la misma conexión y también quedan protegidos.
- Cada `REQUIRES_NEW` abre otra conexión y le pasa la misma clínica. Las de solo lectura también la reciben.
- Si `set_config` falla, la transacción no empieza: Spring la deshace y cierra la sesión.
- Lo que va sin transacción (`SUPPORTS` o `NOT_SUPPORTED` sin una exterior, `JdbcClient` suelto) ve cero filas: falla cerrado, sin error. Los métodos de los repositorios de Spring Data abren su propia transacción y no tienen este problema.
- Un `EntityManager` abierto a mano con su propia `EntityTransaction` no pasa por Spring: no recibe clínica y no ve filas.
- Un query más por transacción.
- Los tests pasan por el camino real. Solo dos tests de `TenantScopedEntityTests` simulan un fallo del RLS para probar el filtro de Hibernate por separado.

## Alternativas descartadas

- Envolver el `DataSource` y fijar la clínica al sacar la conexión del pool: depende de limpiarla al devolverla, y un fallo a medias la deja puesta.
- `connectionInitSql` o `SET` de sesión: el valor sobrevive a la transacción y pasa a la siguiente petición.
- `TransactionExecutionListener.afterBegin`: si falla, la transacción ya empezó y no queda registrada para cerrarse, así que la conexión no vuelve al pool.
- Fijar el dialecto en el `JpaTransactionManager` con un `TransactionManagerCustomizer`: `afterPropertiesSet` lo sustituye por el de la factoría.
