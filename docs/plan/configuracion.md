# Apartado de Configuración

Dentasoft tiene un apartado **Configuración** con secciones por área. Cada clínica configura lo suyo (incluido el envío de emails y WhatsApp); el administrador de la plataforma configura lo común; cada usuario, sus preferencias. Este documento define las secciones, quién ve y edita cada una, y qué tarea del plan la construye.

## Principios

- **Tres niveles con herencia**: plataforma (valores por defecto) → clínica (sobrescribe) → usuario (solo preferencias personales). Si la clínica no define un valor, se usa el de la plataforma.
- **Cada módulo es dueño de su configuración**: los datos viven en tablas tipadas del propio módulo (por ejemplo, `channel_config` en `notifications`), no en un almacén genérico de clave-valor. El apartado de Configuración solo reúne las pantallas.
- **Secretos de solo escritura**: tokens y contraseñas (por ejemplo, el token de WhatsApp de una clínica) se guardan cifrados, nunca se devuelven al navegador y solo se muestran como «configurado · terminado en ••••1234». Para cambiarlos se escribe uno nuevo.
- **Cambios auditados**: cada cambio de configuración queda en `audit_log` (quién, cuándo, qué sección; sin valores secretos).
- **Probar antes de activar**: los canales de comunicación tienen un botón «Enviar prueba» y no se pueden activar si la prueba no ha funcionado.
- **Validación en el servidor**: la pantalla ayuda, pero el backend valida siempre y aplica los permisos.

## Secciones y permisos

Leyenda: **E** = ve y edita · **V** = solo ve · — = no ve la sección.

| Sección | Contenido | PLATFORM_ADMIN | ADMIN_CLINICA | ODONTOLOGO | HIGIENISTA | RECEPCION | FACTURACION | Tarea |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Clínica | Datos generales y fiscales (los fiscales, solo lectura: los corrige la plataforma, M01-T11), logo, horario, zona horaria, idioma, gabinetes | — | E | V | V | V | V | M01-T06, M01-T10, M05-T06 |
| Usuarios y roles | Invitar, cambiar rol, desactivar | — | E | — | — | — | — | M02-T05, M02-T08 |
| Profesionales | Colegiado, área clínica, color en agenda | — | E | V | V | V | V (1) | M02-T06, M02-T08 |
| Agenda | Duración de cita por defecto, horarios de profesionales, festivos y cierres | — | E | V | V | E (festivos y bloqueos) | — | M06-T03 |
| Comunicaciones | Email y WhatsApp: activación, remitente, credenciales, tipos de mensaje, recordatorios, horario permitido, plantillas, prueba de envío | — | E | — | — | V | — | M07-T07 |
| Catálogo de tratamientos | Tratamientos, precios, duración, ámbito | — | E | V | V | V | E (precios) | M08-T03 |
| Presupuestos | Validez por defecto, condiciones, prefijo de numeración, impuestos por defecto | — | E | V | — | — | E | M11-T06 |
| Consentimientos y firma | Plantillas de consentimiento, tablets emparejadas | — | E | V (plantillas) | — | V (tablets) | — | M12-T03, M12-T07 |
| Privacidad y RGPD | Textos legales, finalidades de consentimiento, plazo de conservación, exportación de datos | — | E | — | — | — | — | M04-T07 |
| Seguridad y auditoría | Registro de auditoría consultable, sesiones activas de usuarios | — | E | — | — | — | — | M01-T09 |
| Preferencias personales | Idioma, vista de agenda por defecto, profesional o gabinete por defecto; el odontólogo, también el color de su ficha en la agenda | E | E | E | E | E | E | M02-T10 |
| Plataforma | Clínicas, proveedor de email común, app de Meta y webhook, plantillas por defecto, funciones activables por clínica | E | — | — | — | — | — | M01-T05, M02-T11, M02-T08, M07-T07 |

(1) FACTURACION ve los profesionales porque los necesita en presupuestos y facturas (M11).

`PLATFORM_ADMIN` nunca ve datos clínicos ni la configuración interna de una clínica salvo que la clínica le dé acceso de soporte (fuera del MVP).

## Comunicaciones: qué se configura por clínica

| Ajuste | Opciones |
| --- | --- |
| Canal de email | Activado o no; nombre del remitente (por defecto, nombre de la clínica); `Reply-To`; pie y firma; logo. El envío sale por el proveedor común de la plataforma (Resend) desde el dominio de envío configurado |
| Canal de WhatsApp | Activado o no; **modo**: número de prueba de la plataforma (solo desarrollo y pruebas) · **credenciales propias de la clínica** (su `phone_number_id`, `waba_id` y token de su app de Meta) · Embedded Signup (cuando Dentasoft sea Tech Provider, ver M13-T06) |
| Tipos de mensaje | Activar o desactivar por tipo: recordatorio de cita, confirmación, cambio, cancelación, presupuesto enviado, documento pendiente de firma |
| Recordatorios | Cuándo (por ejemplo 48 h y 2 h antes), canal preferido y alternativo si el primero falla o no hay consentimiento |
| Horario permitido | Franja en la que se pueden enviar mensajes (por defecto 9:00–21:00, zona de la clínica) |
| Plantillas | Textos de email editables con variables; para WhatsApp, elegir entre las plantillas aprobadas en Meta y ver su estado |
| Prueba | «Enviar prueba» a un email o teléfono de la propia clínica antes de activar |
