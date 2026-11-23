# M04 · Pacientes

**Objetivo**: gestionar la ficha completa del paciente con sus antecedentes, alergias y consentimientos, protegiendo los datos sensibles y registrando quién accede.
**Depende de**: M02
**Paquete backend**: `patients` · **Frontend**: `features/patients`
**FHIR**: Patient, AllergyIntolerance, Consent

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `patient` | `tenant_id`, `record_number` (nº de historia por clínica), nombre y apellidos, `birth_date`, `sex`, `national_id` (cifrado) + `national_id_hash` (búsqueda), contacto, dirección, `preferred_language`, `deleted_at` |
| `patient_contact` | `tenant_id`, `patient_id`, tipo (tutor, familiar, emergencia), nombre, teléfono |
| `medical_history` | `tenant_id`, `patient_id`, antecedentes, medicación, observaciones (cifrados), `updated_by` |
| `allergy` | `tenant_id`, `patient_id`, sustancia, gravedad, notas (cifradas) |
| `consent_record` | `tenant_id`, `patient_id`, `purpose` (tratamiento de datos, recordatorios, comunicaciones), `channel` (email, WhatsApp, SMS), `granted`, `granted_at`, `revoked_at`, `evidence_document_id` |

## Tareas

### M04-T01 · Modelo de pacientes y cifrado de campos

- **Estado**: pendiente
- **Depende de**: M01-T04, M02-T04
- **Descripción**: migraciones con RLS, entidades y conversor JPA de cifrado AES-GCM para campos sensibles (clave por variable de entorno, con versión de clave para poder rotarla) e índice ciego (HMAC) para buscar por DNI/NIE.
- **Criterios de aceptación**:
  - [ ] En la base de datos, los campos sensibles no se leen en claro
  - [ ] Buscar por DNI funciona sin descifrar toda la tabla
  - [ ] Test de aislamiento entre clínicas

### M04-T02 · API de pacientes y búsqueda

- **Estado**: pendiente
- **Depende de**: M04-T01
- **Descripción**: alta, edición y consulta de pacientes con número de historia correlativo por clínica, y búsqueda paginada por nombre, teléfono, número de historia y DNI.
- **Criterios de aceptación**:
  - [ ] Detección de posibles duplicados al dar de alta (mismo DNI o nombre + fecha de nacimiento)
  - [ ] `RECEPCION` no ve antecedentes ni alergias detallados; `ODONTOLOGO` sí
  - [ ] Validaciones (fechas, teléfono, email, DNI/NIE)

### M04-T03 · Antecedentes y alergias

- **Estado**: pendiente
- **Depende de**: M04-T01
- **Descripción**: API para antecedentes médicos y alergias con historial de cambios.
- **Criterios de aceptación**:
  - [ ] Las alergias graves se exponen como alerta para el resto de módulos (odontograma, planes)
  - [ ] Cada lectura y cambio queda en `audit_log`

### M04-T04 · Consentimientos RGPD

- **Estado**: pendiente
- **Depende de**: M04-T01
- **Descripción**: registro de consentimientos por finalidad y canal, con fecha, revocación y enlace al documento firmado (M12); API pública del módulo para consultar «¿puedo escribirle por este canal para esto?».
- **Criterios de aceptación**:
  - [ ] Revocar un consentimiento corta los envíos de ese canal (verificado en M07)
  - [ ] Historial completo de concesiones y revocaciones

### M04-T05 · Baja lógica, conservación y derecho de acceso

- **Estado**: pendiente
- **Depende de**: M04-T02
- **Descripción**: baja lógica del paciente (nunca borrado físico), regla de conservación documentada (mínimo 5 años desde la última asistencia según la Ley 41/2002, ajustable por comunidad) y exportación de los datos del paciente en JSON.
- **Criterios de aceptación**:
  - [ ] Un paciente dado de baja no aparece en búsquedas normales pero sí para el administrador
  - [ ] La exportación incluye todos los módulos que ya existan

### M04-T06 · Pantallas de pacientes

- **Estado**: pendiente
- **Depende de**: M04-T02, M02-T07
- **Descripción**: listado con búsqueda y filtros, alta y edición, y ficha con pestañas (datos, antecedentes y alergias, consentimientos; y huecos preparados para documentos, citas, odontograma y planes).
- **Criterios de aceptación**:
  - [ ] Alerta visible de alergias graves en la cabecera de la ficha
  - [ ] Formularios accesibles (teclado, etiquetas) y textos con Transloco
