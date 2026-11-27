# M07 · Notificaciones (email y WhatsApp)

**Objetivo**: avisar a los pacientes por email y WhatsApp (recordatorios, confirmaciones, documentos) respetando su consentimiento, con reintentos y trazabilidad.
**Depende de**: M03 (URL pública para webhooks), M06
**Configuración**: sección «Comunicaciones» del [apartado de Configuración](configuracion.md)
**Paquete backend**: `notifications` · **Frontend**: `features/settings/notifications`
**FHIR**: Communication

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `channel_config` | `tenant_id`, `channel`, `enabled`, `verified_at` (última prueba correcta); email: `sender_name`, `reply_to`, pie; WhatsApp: `mode` (prueba de plataforma, credenciales propias, Embedded Signup), `phone_number_id`, `waba_id`, token cifrado (solo escritura) |
| `notification_settings` | `tenant_id`, tipos de mensaje activos, horas de recordatorio, canal preferido y alternativo, horario permitido |
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
  - [ ] Validar el email con un formato estricto (con dominio de nivel superior) antes de usarlo como `Reply-To`

### M07-T03 · Canal de WhatsApp

- **Estado**: pendiente
- **Depende de**: M07-T01
- **Descripción**: cliente de la WhatsApp Cloud API (versión por variable, ahora `v25.0`), envío de plantillas con variables y botones, y envío con las credenciales de la clínica según su modo: número de prueba de la plataforma (solo desarrollo y pruebas) o **credenciales propias de la clínica** (su app de Meta). Embedded Signup queda para cuando Dentasoft sea Tech Provider (M13-T06). Versión de la API por variable de entorno.
- **Criterios de aceptación**:
  - [ ] Token guardado cifrado y nunca en logs
  - [ ] Plantilla `recordatorio_cita` creada y aprobada en Meta (en español)
  - [ ] Dos clínicas con credenciales distintas envían cada una desde su número (test con el cliente simulado)

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
- **Nota**: si el recordatorio necesita datos del profesional, `ActivePractitioners` (M02-T06) no sirve tal cual: exige un usuario con sesión iniciada y membresía en la clínica (`@PreAuthorize`). La tarea programada no tiene usuario, así que necesitará otra vía, por ejemplo un método de la API pública sin `@PreAuthorize` pensado para procesos que fijan la clínica explícitamente. Se decide en esta tarea.

### M07-T06 · Pantallas de notificaciones

- **Estado**: pendiente
- **Depende de**: M07-T05
- **Descripción**: pestaña de mensajes enviados en la ficha del paciente y listado general de envíos con su estado. La configuración de canales está en M07-T07.
- **Criterios de aceptación**:
  - [ ] Historial filtrable por canal, tipo y estado
  - [ ] `RECEPCION` puede reenviar un mensaje fallido

### M07-T07 · Configuración de comunicaciones por clínica

- **Estado**: pendiente
- **Depende de**: M07-T03, M02-T09
- **Descripción**: sección «Comunicaciones» del apartado de Configuración (ver `configuracion.md`): activar email y WhatsApp por clínica, remitente y `Reply-To`, modo de WhatsApp y credenciales propias (token de solo escritura), tipos de mensaje activos, horas de recordatorio, canal preferido y alternativo, horario permitido, plantillas y botón «Enviar prueba». A nivel de plataforma, `PLATFORM_ADMIN` configura el proveedor de email común, la app de Meta (webhook) y las plantillas por defecto.
- **Criterios de aceptación**:
  - [ ] Un canal no se puede activar sin una prueba de envío correcta
  - [ ] El token de WhatsApp nunca vuelve al navegador; solo se muestra «configurado · ••••1234»
  - [ ] Validación de las credenciales contra la API de Meta al guardarlas
  - [ ] Desactivar un canal o un tipo de mensaje detiene los envíos pendientes de ese tipo
  - [ ] Cada cambio queda en `audit_log` sin valores secretos
  - [ ] Solo `ADMIN_CLINICA` edita; `RECEPCION` ve el estado de los canales
