# M10 · Planificación de tratamientos

**Objetivo**: planificar tratamientos por fases y sesiones sobre el odontograma, enlazarlos con la agenda y registrar lo realizado como historia clínica.
**Depende de**: M06, M09
**Paquete backend**: `treatments` (planes), `clinical` (procedimientos y notas) · **Frontend**: `features/treatment-plans`
**FHIR**: CarePlan, ServiceRequest, Procedure

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `treatment_plan` | `tenant_id`, `patient_id`, `title`, `status` (borrador, propuesto, aceptado, en curso, terminado, cancelado), `practitioner_id`, `accepted_quote_version_id` |
| `plan_phase` | `tenant_id`, `plan_id`, `position`, `name`, `goal` |
| `plan_item` | `tenant_id`, `phase_id`, `treatment_id`, `tooth`, `surfaces`, `planned_sessions`, `unit_price`, `discount`, `status` (pendiente, programado, en curso, realizado, cancelado) |
| `procedure_record` | `tenant_id`, `patient_id`, `plan_item_id`, `appointment_id`, `performed_at`, `performed_by`, `notes` |
| `clinical_note` | `tenant_id`, `patient_id`, `appointment_id`, `author_id`, `content` (cifrado), `closed_at`, `amends_note_id` |

## Tareas

### M10-T01 · Modelo de planes y máquina de estados

- **Estado**: pendiente
- **Depende de**: M08-T01, M09-T01
- **Descripción**: migraciones con RLS y transiciones permitidas de plan e ítems; el precio se copia del catálogo al planificar.
- **Criterios de aceptación**:
  - [ ] Transiciones inválidas rechazadas con 409
  - [ ] Cambiar el precio del catálogo no altera planes existentes

### M10-T02 · API de planificación

- **Estado**: pendiente
- **Depende de**: M10-T01
- **Descripción**: crear plan (vacío o desde hallazgos del odontograma), fases ordenables, ítems con pieza y superficies validadas según el ámbito del tratamiento, totales y sesiones estimadas; al planificar se crea el hallazgo `planificado`.
- **Criterios de aceptación**:
  - [ ] Un ítem de ámbito `superficie` sin superficies se rechaza
  - [ ] Varios planes alternativos por paciente; solo uno aceptado a la vez

### M10-T03 · Enlace con la agenda

- **Estado**: pendiente
- **Depende de**: M10-T02, M06-T02
- **Descripción**: programar sesiones de un ítem como citas (con duración del catálogo) y, al finalizar la cita, registrar `procedure_record` y pasar el hallazgo a `realizado` mediante eventos.
- **Criterios de aceptación**:
  - [ ] Cancelar la cita devuelve el ítem a `pendiente`
  - [ ] El odontograma refleja lo realizado sin acciones manuales

### M10-T04 · Notas clínicas

- **Estado**: pendiente
- **Depende de**: M10-T01
- **Descripción**: notas por visita, cifradas, que se cierran al firmarlas el profesional o a las 24 horas; tras cerrarse solo admiten adendas.
- **Criterios de aceptación**:
  - [ ] Una nota cerrada no se puede editar (test en base de datos)
  - [ ] Cada lectura queda en `audit_log`

### M10-T05 · Pantallas de planes

- **Estado**: pendiente
- **Depende de**: M10-T02, M09-T06
- **Descripción**: editor de plan con fases y arrastrar y soltar, vista de progreso (realizado frente a pendiente), comparación de planes alternativos y acceso a programar sesiones.
- **Criterios de aceptación**:
  - [ ] Total por fase y total del plan siempre visibles
  - [ ] Textos con Transloco y formato de moneda en euros
