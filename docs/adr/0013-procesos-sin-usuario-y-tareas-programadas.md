# 0013 — Procesos sin usuario clínica a clínica y tareas programadas con ShedLock

Fecha: 2026-11-25 · Estado: aceptada

## Contexto

Los recordatorios de citas, las caducidades o los reintentos de envío son procesos sin usuario que recorren todas las clínicas (M01-T08). Hay cuatro restricciones:

- `dentasoft_app` solo ve la clínica activa (RLS sobre `clinic.id`), así que no puede listar las clínicas. Ver todas es cosa de `dentasoft_platform` (ADR 0011).
- `platform` ya depende de `tenancy`, y Spring Modulith (`ModularityTests`) no admite ciclos.
- La clínica se fija antes de abrir la transacción (ADR 0009 y 0010), y `runAs` rechaza fijarla dentro de una ya abierta. Por eso un `@ApplicationModuleListener`, que abre la suya antes del método, no puede trabajar con datos de una clínica.
- Con varias réplicas, cada una ejecutaría las tareas programadas por su cuenta.

## Decisión

- **Lista de clínicas**: la interfaz `ActiveClinicIds` está en la raíz de `tenancy` y la implementa `platform` en `internal`, en su unidad de persistencia de solo lectura. Devuelve solo los ids de las clínicas `ACTIVE`, ordenados. Las suspendidas no se procesan. `dentasoft_app` no recibe ningún permiso nuevo.
- **Una clínica, una transacción**:
  - `ClinicTransactions.run/call` hace `runAs(clinicId)` y dentro abre su propio `TransactionTemplate`. Lo rechaza si ya hay una clínica activa, una transacción abierta o la sincronización de transacciones activa (`SUPPORTS`), que compartiría la sesión de Hibernate.
  - `ClinicBatch.forEachActiveClinic` lo aplica a cada clínica activa. Nunca hay una transacción exterior que abarque varias, así que un fallo solo deshace el trabajo de su clínica.
- **Fallos**:
  - Se captura `RuntimeException` y se registra el job, el id de la clínica y la clase de la excepción, nunca su mensaje, que puede llevar datos personales. Después se sigue con la siguiente clínica.
  - Al final hay un resumen con las clínicas que fallaron. `ClinicBatch.Result` las devuelve.
  - Un `Error` (`OutOfMemoryError`…) no se captura y detiene el proceso. Si llega envuelto en una `RuntimeException`, como causa o como la excepción original de una `TransactionSystemException`, también.
- **Actor de auditoría**: `SYSTEM`, porque no hay usuario. Desde M02-T02 se registra `USER` cuando lo hay. Un lote que se lance desde una petición se audita como el usuario que lo lanzó (ADR 0015).
- **Listeners que trabajan con datos de una clínica**:
  - Se declaran con `@Async` y `@TransactionalEventListener`, sin `@Transactional`.
  - El evento lleva el `clinicId`, y el listener llama a `ClinicTransactions.run(event.clinicId(), …)`.
- **Una sola ejecución con varias réplicas**: ShedLock (`shedlock-spring` y `shedlock-provider-jdbc-template`), con una tarea `@Scheduled` y `@SchedulerLock(name = …)`. Las tareas se configuran en `SchedulingConfiguration`:
  - Usa la hora de la base de datos (`usingDbTime()`): las réplicas no dependen de su reloj.
  - No tiene un gestor de transacciones asignado. ShedLock usa uno propio para la fila del bloqueo, que no es un bean y no lo ve `TenantAwareJpaDialectPostProcessor`.
  - Por defecto, el bloqueo dura como máximo 10 minutos (`defaultLockAtMostFor`). Si una réplica muere con el bloqueo, la tarea vuelve a ejecutarse pasado ese tiempo.
  - Y como mínimo 30 segundos (`defaultLockAtLeastFor`). Los disparadores de dos réplicas no saltan en el mismo instante: sin un mínimo, una tarea corta liberaría el bloqueo antes de que salte el de la otra réplica, que la ejecutaría otra vez. Las tareas que se ejecutan cada 30 segundos o más a menudo lo bajan claramente por debajo de su intervalo (por ejemplo, a la mitad). Si una tarea baja `lockAtMostFor` por debajo de 30 segundos, tiene que bajar también `lockAtLeastFor`: ShedLock rechaza la combinación cada vez que intenta coger el bloqueo, y la tarea no se ejecuta.
- **Tabla de bloqueos fuera de `public`** (V005):
  - `scheduler.shedlock`, sin datos de clínica y sin RLS.
  - `dentasoft_app` solo tiene `USAGE` en el esquema y `SELECT`, `INSERT` y `UPDATE` en la tabla, sin `DELETE` ni `CREATE`.
  - `RowLevelSecurityGuardTests` lo trata como excepción explícita: falla si aparece otro esquema, si `scheduler` tiene otra tabla o si cambian esos permisos.
- **Horas en `timestamp`, no en `timestamptz`**:
  - Con la hora de la base de datos, ShedLock escribe y compara `timezone('utc', CURRENT_TIMESTAMP)`, que no tiene zona.
  - En una columna `timestamptz`, ese valor se interpretaría en la zona de cada sesión, que el driver JDBC toma de la JVM. Una réplica más al este vería caducado el bloqueo de otra y ejecutaría la tarea otra vez, como reproduce `SchedulerLockTests.aLockHoldsForReplicasInAnyTimeZone`.
  - En `timestamp`, todos los valores están en UTC. Es la segunda excepción a la regla de `timestamptz` de `AGENTS.md`.
- **Versión fijada: ShedLock 7.11.0** (07/11/2026):
  - Es la más reciente con al menos 7 días, como pide la política de Dependabot (`cooldown`). La 7.12.0 tenía 2 días.
  - Licencia Apache 2.0, mantenimiento activo (varias versiones al año).
  - Está compilada con Spring 7.0 y probada con Boot 4.1. Los tests de M01-T08 la verifican con Boot 4.2 (Spring 7.1).

## Consecuencias

- **Si un listener falla, el evento se pierde**: no hay reintentos. Su trabajo se deshace y nadie vuelve a ejecutarlo.
  - Para tener reintentos hay que activar el registro de publicaciones de eventos de Spring Modulith (`spring-modulith-starter-jpa`), que guarda cada publicación hasta que el listener termina.
  - La primera tarea que publica eventos importantes es M06-T02, y lo tiene anotado.
- Una tarea programada que trabaja con datos de clínicas tiene dos partes:
  - `@Scheduled` y `@SchedulerLock` para ejecutarse una sola vez.
  - `ClinicBatch` para recorrer las clínicas activas.
- Las clínicas se procesan una tras otra. Si con muchas clínicas eso se queda corto, se puede paralelizar más adelante sin cambiar la regla de una clínica por transacción.
- Si una clínica pasa a suspendida mientras el proceso está en marcha, puede procesarse una última vez: la lista se lee al empezar.

## Alternativas descartadas

- **Dar a `dentasoft_app` permiso para listar todas las clínicas**: rompe el aislamiento del RLS sobre `clinic`, justo la tabla que lo define.
- **Que `tenancy` llame directamente a `platform`**: crea un ciclo entre módulos.
- **Una transacción para todo el proceso**: `runAs` lo impide, y un fallo desharía el trabajo de todas las clínicas.
- **La tabla de ShedLock en `public` con RLS**: no tiene clínica a la que pertenecer y obligaría a debilitar el guardián.
- **Columnas `timestamptz` con la hora de la base de datos**: fallan con réplicas en zonas distintas (ver arriba).
- **Hora de la JVM en lugar de la de la base de datos**: depende de que los relojes de las réplicas estén sincronizados.
- **Un `@ApplicationModuleListener` con un `runAs` dentro**: su transacción ya está abierta y `runAs` lo rechaza.
