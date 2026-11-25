# 0012 — Registro de auditoría inmutable, sin datos personales y en la transacción del cambio

Fecha: 2026-11-25 · Estado: aceptada

## Contexto

Dentasoft trata datos de salud: todo acceso y todo cambio a datos sensibles debe quedar registrado, y un acceso sin registrar no es aceptable (M01-T07). El propio registro no puede convertirse en otra copia de datos personales, y nadie, ni siquiera una migración, debe poder reescribir el historial sin que se note.

Además hay tres restricciones técnicas:

- Las lecturas suelen ir en `@Transactional(readOnly = true)`, y una transacción de solo lectura de PostgreSQL no admite `INSERT`.
- La plataforma (ADR 0011) trabaja sin clínica activa, con su propio rol y su propia unidad de persistencia.
- El pool de conexiones es pequeño: una lectura que retenga dos conexiones a la vez puede bloquearlo bajo carga.

## Decisión

- **Tabla `audit_log`** (V004), alineada con FHIR AuditEvent:

  | Columna | Contenido | FHIR AuditEvent |
  | --- | --- | --- |
  | `id` | UUID v7 | `id` |
  | `tenant_id` | clínica del registro | — |
  | `occurred_at` | cuándo ocurrió, con el reloj de la base de datos | `recorded` |
  | `actor_type` | `USER`, `SYSTEM` o `PLATFORM` | `agent.type` |
  | `actor_id` | `sub` de Keycloak, vacío hasta M02 | `agent.who` |
  | `action` | `CREATE`, `READ`, `UPDATE` o `DELETE` | `action` |
  | `entity_type` y `entity_id` | qué registro | `entity.what` |
  | `outcome` | `SUCCESS` o `DENIED` | `outcome` |
  | `request_id` | id de la petición | — |
  | `details` | jsonb | `subtype` y otros detalles |

  - Es inmutable: no tiene `updated_*` ni `@Version` y no extiende `BaseEntity`.
  - Índice `(tenant_id, occurred_at)` y RLS con `tenant_isolation`.
  - Sin clave foránea a `clinic`: el historial sobrevive a la fila.
- **La clínica y la hora las pone la base de datos**: `tenant_id` toma por defecto `app.tenant_id` y `occurred_at`, `clock_timestamp()`. Quien escribe no las elige, así que no puede fechar una entrada en el pasado, y sin clínica activa la fila se rechaza.
- **Sin datos personales por diseño**:
  - Ninguna columna admite texto libre: o son códigos con `CHECK`, o UUID.
  - `details` solo se escribe desde el record `AuditDetails`, cuyos componentes solo pueden ser UUID, enumerados, números, booleanos u otros records con la misma regla. `AuditDetailsTests` falla si se añade un `String`, una fecha o una colección.
  - **No se guarda la IP**, ni en claro ni con HMAC. La clave del límite de peticiones solo es obligatoria con el límite activado, y si rota, los hash dejan de ser comparables justo durante los años que se conserva el registro. Además, un hash de IP sigue siendo un dato seudónimo.
  - La correlación con una petición se hace con `request_id`, que solo se guarda si lo generó el servidor. El id que envía un cliente, aunque tenga forma de UUID, podría repetir el de otra petición, así que nunca llega al registro. Los logs indican el origen del id en `requestIdOrigin`.
- **Solo se añade**:
  - `dentasoft_app` solo tiene `INSERT`, y solo de las columnas que rellena: ni `tenant_id`, ni `occurred_at`, ni `details`, así que ni un SQL escrito a mano puede meter texto en `details`. V001 le daba por defecto `SELECT`, `INSERT` y `UPDATE` sobre toda la tabla, y se revocan. El `SELECT` lo concederá M01-T09 para consultar el registro de la propia clínica.
  - Un trigger rechaza `UPDATE`, `DELETE` y `TRUNCATE` a cualquier rol, también al dueño. Reescribir el historial exige un `DROP TRIGGER` en una migración, a la vista en la revisión.
- **Los cambios se registran en la misma transacción**:
  - Un listener de Hibernate (PostInsert, PostUpdate y PostDelete) actúa sobre las entidades `@AuditedEntity` y escribe el registro con JDBC en la misma conexión, durante el flush.
  - Si el registro falla, falla el cambio; si el cambio se deshace, el registro también.
  - Lo añade un `BeanPostProcessor` al `EventListenerRegistry` de cada fábrica de `EntityManager` en cuanto se crea. Así no se puede saltar ni con la inicialización diferida, y `AuditListenerRegistrationTests` comprueba que está. No se usa la lista de integradores de `TenancyHibernateSettings`, que instala el `TenantWriteGuard`.
- **Las lecturas se registran después de leer**:
  - `@AuditedRead` es un `MethodInterceptor` de Spring AOP con este orden: auditoría, `@PreAuthorize`, `@Transactional`.
  - La lectura termina y devuelve su conexión al pool; después se escribe el registro en una transacción propia de escritura, que vuelve a fijar la clínica activa (ADR 0010).
  - Si el registro falla, se lanza la excepción y los datos no se devuelven.
  - Una lectura que `@PreAuthorize` deniega queda como `DENIED`.
  - `AuditService.recordRead` sirve para los casos en que la anotación no encaja.
- **Excepción documentada**: si el método se llama desde dentro de una transacción ya abierta, el registro va en `REQUIRES_NEW` y ese hilo retiene dos conexiones un momento. Por eso las lecturas auditadas se llaman fuera de cualquier transacción.
- **Acciones de la plataforma**:
  - Crear, suspender y reactivar una clínica se registra en la transacción de plataforma, con `PlatformAuditRecord` en `platform.persistence`. El `tenant_id` es la clínica afectada y el subtipo va en `details`.
  - `dentasoft_platform` tiene `INSERT` por columnas (sin `occurred_at`) y la política `platform_insert`. Esa política solo admite entradas `PLATFORM`, sobre la propia clínica bajo la que se guardan (`entity_type = 'CLINIC'` y `entity_id = tenant_id`) y de alta o cambio (`CREATE` o `UPDATE`). No puede leer el registro.
- **Actor**: hasta M02 no hay usuarios, así que los registros de la aplicación son `SYSTEM` sin id y los de la plataforma, `PLATFORM` sin id. M02 los rellenará con el `sub` (`CurrentActor` y `PlatformAuditRecord`).

## Consecuencias

- Ningún cambio de una entidad auditada ni ninguna lectura auditada sale sin registrar, y el registro no puede contener nombres, notas ni diagnósticos.
- **Lo que el listener no ve**: el `UPDATE` o `DELETE` masivo en JPQL (`@Modifying`, `deleteAllInBatch`) y el SQL nativo sobre una tabla de entidad auditada no se registran.
  - `AuditedRepositoriesTests` impide las consultas `@Modifying` en los repositorios de entidades auditadas y las llamadas a sus borrados en lote.
  - El JPQL o SQL escrito a mano con el `EntityManager` o `JdbcClient` queda a la revisión de código.
  - Por esta regla, `replaceWeek` borra el horario entidad a entidad.
- **Lo que la base de datos no impide**: la aplicación elige `actor_type` y `actor_id` dentro de su clínica. Si el código de la aplicación falla o lo comprometen, puede escribir entradas falsas de `SYSTEM` o `USER`, y también de `PLATFORM`. Prohibirle solo `PLATFORM` apenas añadiría protección, y el guardián de RLS no admite políticas restrictivas para la app. La autenticidad del actor depende del código (M02) y de la revisión.
- **Lo que `@AuditedRead` no ve**:
  - una llamada desde la propia clase (límite de los proxies de Spring AOP);
  - una lectura que termina con otra excepción, como no encontrado: no deja entrada;
  - una lectura de varios registros, para la que M01-T09 necesitará una forma de registrar listados.

  Un aumento de versión forzado por un modo de bloqueo tampoco dispara `PostUpdate`. La fila de `clinic_settings` que crea la plataforma no tiene entrada propia: la cubre la entrada `CREATE` de la clínica.
- Una lectura auditada cuesta un `INSERT` en una transacción aparte, con su propia conexión. El test con el pool de 2 conexiones y lecturas en paralelo comprueba que no se bloquea.
- Los tests no pueden limpiar el registro: miran solo las entradas de los ids que crean.
- `entity_type` acepta cualquier código en mayúsculas, así que cada módulo añade sus entidades a `AuditEntityType` sin migración.
- **Fuera de alcance, pendiente de su propia tarea**:
  - Retención y particionado. Borrar o desacoplar una partición se salta el trigger de `DELETE`, así que hay que diseñarlo junto con él.
  - Cadena de hashes para detectar manipulaciones hechas por un superusuario (`session_replication_role = replica`) o por una migración que quite el trigger.
  - Auditar las escrituras denegadas y las búsquedas (M04).

## Alternativas descartadas

- Escribir el registro de una lectura dentro de su transacción (`REQUIRES_NEW` siempre): cada lectura retiene dos conexiones y el pool se bloquea con carga.
- Auditar las lecturas con el evento `PostLoad` de Hibernate: salta también en cargas internas y no distingue un acceso de negocio.
- Usar AspectJ (`@Aspect`): exige depender de `aspectjweaver`, y Spring AOP ya basta, porque es el mismo mecanismo de `@PreAuthorize` y `@Transactional`.
- Guardar la IP con el HMAC del límite de peticiones: por la rotación de la clave y porque sigue siendo un dato seudónimo (ver Decisión).
- Hacer la entidad de auditoría una `TenantScopedEntity`: heredaría `updated_*` y `@Version`, y la plantilla de aislamiento prueba modificar y borrar, que aquí es imposible por diseño. `AuditLogDatabaseTests` cubre lo mismo para una tabla en la que solo se añade.
