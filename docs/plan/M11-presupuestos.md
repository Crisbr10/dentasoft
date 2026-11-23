# M11 · Presupuestos

**Objetivo**: generar presupuestos a partir de los planes, con versiones inmutables, PDF, envío seguro y aceptación firmada.
**Depende de**: M05, M10
**Paquete backend**: `quotes` · **Frontend**: `features/quotes`

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `quote` | `tenant_id`, `patient_id`, `plan_id`, `number` (correlativo por clínica y año), `status` (borrador, enviado, aceptado, rechazado, caducado) |
| `quote_version` | `tenant_id`, `quote_id`, `version`, líneas (copia de los ítems), descuentos, impuestos, total, `valid_until`, `pdf_document_id`, `sent_at` (inmutable tras enviarse) |
| `quote_acceptance` | `tenant_id`, `quote_version_id`, `accepted_at`, método (firma en tablet, firma remota, registro manual), `signature_evidence_id` |

## Tareas

### M11-T01 · Modelo de presupuestos versionados

- **Estado**: pendiente
- **Depende de**: M10-T01
- **Descripción**: migraciones con RLS; una versión enviada no se puede modificar (permisos de base de datos), cualquier cambio crea una versión nueva; numeración correlativa por clínica y año sin huecos.
- **Criterios de aceptación**:
  - [ ] `UPDATE` sobre una versión enviada falla en la base de datos
  - [ ] Numeración sin duplicados con peticiones concurrentes

### M11-T02 · Generar presupuesto desde el plan

- **Estado**: pendiente
- **Depende de**: M11-T01, M10-T02
- **Descripción**: crear el presupuesto con las líneas del plan (o de una fase), descuentos por línea y global, IVA (exento por defecto en tratamientos sanitarios, configurable) y validez.
- **Criterios de aceptación**:
  - [ ] Totales calculados con `BigDecimal` y redondeo documentado
  - [ ] Tests de cálculo con descuentos e impuestos

### M11-T03 · PDF y envío

- **Estado**: pendiente
- **Depende de**: M11-T02, M05-T04, M07-T02
- **Descripción**: PDF con la identidad de la clínica, líneas, odontograma 2D y condiciones; envío por email o WhatsApp con enlace seguro y caducidad (nunca el PDF adjunto).
- **Criterios de aceptación**:
  - [ ] El PDF de una versión enviada es siempre el mismo (huella guardada)
  - [ ] El enlace caduca y queda registrado quién lo abrió

### M11-T04 · Aceptación y rechazo

- **Estado**: pendiente
- **Depende de**: M11-T03
- **Descripción**: registrar aceptación (firma en tablet con M12, o registro manual con motivo) o rechazo; al aceptar, el plan pasa a `aceptado` y enlaza la versión aceptada.
- **Criterios de aceptación**:
  - [ ] Solo se acepta la última versión enviada y no caducada
  - [ ] Evento `PresupuestoAceptado` publicado

### M11-T05 · Pantallas de presupuestos

- **Estado**: pendiente
- **Depende de**: M11-T02
- **Descripción**: listado por paciente y global con filtros de estado, editor de borrador, vista previa del PDF, historial de versiones y acciones de envío y aceptación.
- **Criterios de aceptación**:
  - [ ] Las versiones enviadas se muestran en solo lectura

### M11-T06 · Configuración de presupuestos

- **Estado**: pendiente
- **Depende de**: M11-T01, M02-T09
- **Descripción**: sección «Presupuestos» del apartado de Configuración: validez por defecto, condiciones y texto legal del pie, prefijo de numeración e impuestos por defecto.
- **Criterios de aceptación**:
  - [ ] Los cambios solo afectan a presupuestos nuevos
  - [ ] Editable por `ADMIN_CLINICA` y `FACTURACION`
