# M07 · Notificaciones (email y WhatsApp)

**Objetivo**: avisar a los pacientes por email y WhatsApp (recordatorios, confirmaciones, documentos) respetando su consentimiento, con reintentos y trazabilidad.
**Depende de**: M03 (URL pública para webhooks), M06
**Paquete backend**: `notifications` · **Frontend**: `features/settings/notifications`
**FHIR**: Communication

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `channel_config` | `tenant_id`, `channel`, `enabled`, configuración (WhatsApp: `phone_number_id`, `waba_id`, token cifrado; email: remitente y `reply_to`) |
| `message_template` | `tenant_id` (o global), `code`, `channel`, `locale`, contenido o nombre de plantilla de Meta |
| `message_log` | `tenant_id`, `patient_id`, `channel`, `template_code`, `provider_message_id`, `status` (en cola, enviado, entregado, leído, fallido), `error`, fechas |

Regla: los mensajes llevan los datos justos (fecha y hora de la cita, nombre de la clínica). Nunca tratamientos, diagnósticos ni documentos adjuntos: solo enlaces seguros.

## Tareas

### M07-T01 · Núcleo de notificaciones

- **Estado**: pendiente
- **Depende de**: M04-T04, M06-T02
- **Descripción**: interfaz `NotificationChannel`, plantillas con i18n, registro de envíos y envío asíncrono desde eventos de Spring Modulith con reintentos; antes de enviar se consulta el consentimiento (M04-T04).
- **Criterios de aceptación**:
  - [ ] Sin consentimiento para el canal, no se envía y queda registrado el motivo
  - [ ] Un fallo del proveedor no bloquea la operación que lo originó

### M07-T02 · Canal de email

- **Estado**: pendiente
- **Depende de**: M07-T01
- **Descripción**: `JavaMailSender` por SMTP (Mailpit en local, Resend en pruebas), plantillas HTML con Thymeleaf, remitente con el nombre de la clínica y `Reply-To` de la clínica.
- **Criterios de aceptación**:
  - [ ] En local, los correos aparecen en Mailpit y nunca salen fuera
  - [ ] En pruebas llegan con SPF, DKIM y DMARC correctos

### M07-T03 · Canal de WhatsApp

- **Estado**: pendiente
- **Depende de**: M07-T01
- **Descripción**: cliente de la WhatsApp Cloud API (versión por variable, ahora `v25.0`), envío de plantillas con variables y botones, y configuración por clínica (en el MVP, el número de prueba).
- **Criterios de aceptación**:
  - [ ] Token guardado cifrado y nunca en logs
  - [ ] Plantilla `recordatorio_cita` creada y aprobada en Meta (en español)

### M07-T04 · Webhooks de WhatsApp y Resend

- **Estado**: pendiente
- **Depende de**: M07-T03, M03-T03
- **Descripción**: endpoint de WhatsApp (verificación GET y firma `X-Hub-Signature-256`) para estados y respuestas; los botones «Confirmar» y «Cancelar» cambian el estado de la cita. Webhook de Resend (firma) para rebotes y quejas, que marcan el email como no válido.
- **Criterios de aceptación**:
  - [ ] Una petición sin firma válida se rechaza con 401
  - [ ] Procesamiento idempotente (el mismo evento dos veces no duplica efectos)

### M07-T05 · Recordatorios programados

- **Estado**: pendiente
- **Depende de**: M07-T02, M07-T03, M01-T08
- **Descripción**: tarea programada con ShedLock que, clínica a clínica, envía recordatorios a las horas configuradas antes de cada cita, por el canal preferido y permitido.
- **Criterios de aceptación**:
  - [ ] Cada cita recibe un solo recordatorio aunque haya varias réplicas
  - [ ] Respeta la zona horaria y no envía de madrugada

### M07-T06 · Pantallas de notificaciones

- **Estado**: pendiente
- **Depende de**: M07-T05
- **Descripción**: configuración de canales y horas de recordatorio por clínica, y pestaña de mensajes enviados en la ficha del paciente con su estado.
- **Criterios de aceptación**:
  - [ ] Solo `ADMIN_CLINICA` configura canales
  - [ ] Historial filtrable por canal y estado
