# 0002 — Multi-clínica con una base de datos, `tenant_id` y Row Level Security

Fecha: 2026-11-23 · Estado: aceptada

## Contexto

Dentasoft es un SaaS para varias clínicas. Una fuga de datos entre clínicas sería una brecha de datos de salud. Hay que aislar sin multiplicar la operación (bases de datos, migraciones, copias).

## Decisión

- Una base de datos PostgreSQL compartida. Cada tabla de negocio lleva `tenant_id`.
- **Dos capas de aislamiento**:
  1. Aplicación: Hibernate `@TenantId` con un `CurrentTenantIdentifierResolver` que lee el `TenantContext`.
  2. Base de datos: `ENABLE` y `FORCE ROW LEVEL SECURITY` con la política `tenant_isolation`, que compara `tenant_id` con `current_setting('app.tenant_id', true)`. Sin clínica activa, no se ve ninguna fila.
- Dos roles de base de datos: `dentasoft_owner` (migraciones) y `dentasoft_app` (aplicación, `NOBYPASSRLS`, sin `DELETE` en datos clínicos ni `UPDATE` en registros inmutables).
- La clínica activa llega en la cabecera `X-Clinic-Id` y se valida contra la membresía del usuario en cada petición.
- Cada módulo incluye un test de aislamiento con Testcontainers.

## Consecuencias

- Un fallo en el código de la aplicación no basta para mezclar datos de clínicas.
- Migraciones y copias de seguridad únicas para todas las clínicas.
- Si una clínica grande exige aislamiento físico, se puede mover a su propio esquema o base de datos.

## Alternativas descartadas

- Una base de datos por clínica: operación y migraciones multiplicadas.
- Solo filtro en la aplicación: un olvido en una consulta expone datos de otra clínica.
