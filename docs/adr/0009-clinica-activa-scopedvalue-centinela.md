# 0009 — Clínica activa en un `ScopedValue` y centinela `NO_CLINIC` en Hibernate

Fecha: 2026-11-24 · Estado: aceptada

## Contexto

La capa de aplicación de ADR 0002 necesita dos piezas: saber qué clínica está activa en cada hilo, y decidir qué hace Hibernate cuando no hay ninguna. La aplicación usa hilos virtuales, y un `ThreadLocal` que nadie limpia pasa la clínica a la siguiente tarea del mismo hilo.

Hibernate 7.4 pregunta la clínica al `CurrentTenantIdentifierResolver` cada vez que abre una sesión, también para entidades sin clínica. Spring Data JPA 4.2 abre sesiones al arrancar para validar los métodos de consulta de los repositorios. Detalle en el issue #10.

## Decisión

- **La clínica activa vive en un `ScopedValue`.** `TenantContext` ofrece `runAs` y `callAs`, y no tiene `set()`. La clínica solo existe dentro del bloque y no se hereda en otros hilos.
- **`runAs` rechaza dos usos peligrosos**, porque Hibernate fija la clínica de la sesión al abrirla:
  - cambiar a otra clínica dentro de una ya activa;
  - fijar la clínica dentro de una transacción ya empezada.
- **Sin clínica activa, el resolver devuelve el centinela `NO_CLINIC`** (el UUID nulo), que nunca es una clínica.
  - Las lecturas de entidades con clínica devuelven vacío.
  - Las escrituras las para `TenantWriteGuard`, un listener `PRE_INSERT`/`PRE_UPDATE`/`PRE_DELETE`, con `NoActiveClinicException`.
  - Ninguna clínica es «root».
- **`@EnableAsync`** para que `@Async` y `@ApplicationModuleListener` se ejecuten en su propio hilo, sin la clínica de quien los llama.

## Consecuencias

- La clínica no puede quedarse en un hilo ni pasar a otra petición.
- La aplicación arranca normalmente. La plataforma, los jobs y el registro de eventos pueden abrir sesiones sin clínica para lo que no pertenece a ninguna.
- Leer sin clínica no da error: un `runAs` olvidado parece «no hay datos». Escribir sí falla.
- Hay un valor especial que nunca puede ser una clínica real: `runAs` lo rechaza y los id de clínica son UUID v7.
- Un `@ApplicationModuleListener` no puede fijar la clínica, porque su transacción ya está abierta. Hasta M01-T08, los listeners que tocan datos de una clínica se escriben con `@Async` y `@TransactionalEventListener`.
- El filtro de Hibernate no cubre las consultas nativas, `JdbcClient`, el SQL propio, un `INSERT` en JPQL ni un `UPDATE` en JPQL que asigna `tenantId`: ahí solo protege el RLS de ADR 0002.

## Alternativas descartadas

- `ThreadLocal`: depende de limpiarlo en un `finally`, y un olvido pasa la clínica a otra tarea del mismo hilo.
- Un resolver que lanza excepción sin clínica, con `bootstrap-mode=lazy`: rompe la plataforma, los jobs y el registro de eventos de Modulith, y retrasa hasta el primer uso los errores de consulta.
- `isRoot` para la plataforma: quita el filtro de clínica por completo.
