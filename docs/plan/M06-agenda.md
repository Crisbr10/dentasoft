# M06 · Agenda y citas

**Objetivo**: gestionar las citas por profesional y gabinete sin solapes, con estados claros y eventos que alimenten recordatorios y planes de tratamiento.
**Depende de**: M04 (y M02 para profesionales y M01 para gabinetes)
**Paquete backend**: `scheduling` · **Frontend**: `features/scheduling`
**FHIR**: Appointment, Schedule, Slot

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `appointment` | `tenant_id`, `patient_id`, `practitioner_id`, `treatment_room_id`, `starts_at`, `ends_at`, `status`, `reason`, `plan_item_id` (opcional), `notes` |
| `appointment_status_history` | `tenant_id`, `appointment_id`, `from_status`, `to_status`, `changed_at`, `changed_by` |
| `availability` | `tenant_id`, `practitioner_id`, día de la semana, franjas, vigencia |
| `schedule_block` | `tenant_id`, `practitioner_id` o `treatment_room_id`, intervalo, motivo (vacaciones, formación…) |

Estados: `programada` → `confirmada` → `en_sala` → `en_curso` → `finalizada`; además `no_presentada` y `cancelada`.

## Tareas

### M06-T01 · Modelo de citas sin solapes

- **Estado**: pendiente
- **Depende de**: M04-T01, M02-T06, M01-T06
- **Descripción**: migraciones con RLS y restricciones de exclusión de PostgreSQL (`btree_gist` + `tstzrange`) para impedir solapes del mismo profesional o gabinete entre citas activas.
- **Criterios de aceptación**:
  - [ ] Dos citas solapadas del mismo profesional fallan en la base de datos aunque la app no lo compruebe
  - [ ] Citas canceladas no bloquean el hueco

### M06-T02 · API de citas y estados

- **Estado**: pendiente
- **Depende de**: M06-T01
- **Descripción**: crear, mover, cambiar de estado y cancelar citas con transiciones válidas e historial; eventos `CitaCreada`, `CitaMovida`, `CitaCancelada`, `CitaFinalizada`.
- **Criterios de aceptación**:
  - [ ] Una transición no permitida devuelve 409 con `ProblemDetail`
  - [ ] Eventos publicados en el registro de eventos de Spring Modulith

### M06-T03 · Disponibilidad y búsqueda de huecos

- **Estado**: pendiente
- **Depende de**: M06-T01
- **Descripción**: horarios de profesionales, bloqueos y búsqueda de huecos libres por profesional, gabinete y duración.
- **Criterios de aceptación**:
  - [ ] Respeta el horario de la clínica, la zona horaria y los cambios de hora
  - [ ] Tests con casos de borde (citas que cruzan el cambio de hora)

### M06-T04 · Elección del componente de calendario

- **Estado**: pendiente
- **Depende de**: M00-T05
- **Descripción**: prueba técnica breve para elegir el calendario: FullCalendar (la vista por profesionales en columnas es de pago), angular-calendar (gratuito, sin vista de recursos) o una rejilla propia sobre Angular CDK. Resultado en un ADR.
- **Criterios de aceptación**:
  - [ ] ADR con licencia, coste, rendimiento con 200 citas y soporte de arrastrar y soltar
  - [ ] Decisión validada con un caso real: día con 4 profesionales y 3 gabinetes

### M06-T05 · Pantallas de agenda

- **Estado**: pendiente
- **Depende de**: M06-T02, M06-T03, M06-T04
- **Descripción**: vistas de día y semana por profesional y por gabinete, crear y mover citas arrastrando, cambio de estado rápido y acceso a la ficha del paciente.
- **Criterios de aceptación**:
  - [ ] Los colores siguen el profesional o el estado (configurable)
  - [ ] Mover una cita a un hueco ocupado muestra el error sin perder el cambio visual

### M06-T06 · Sala de espera en tiempo real

- **Estado**: pendiente
- **Depende de**: M06-T02
- **Descripción**: actualización en vivo de los estados de las citas del día entre puestos (Server-Sent Events repartidos con Redis Pub/Sub para funcionar con varias réplicas).
- **Criterios de aceptación**:
  - [ ] Cambiar una cita a `en_sala` se ve en otro navegador en menos de 2 segundos
  - [ ] Funciona con dos instancias del backend
