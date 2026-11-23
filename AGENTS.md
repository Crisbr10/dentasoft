# Instrucciones para agentes de código — Dentasoft

Este fichero lo leen los agentes de código (Claude Code, Copilot, Gemini…) y cualquier persona que se incorpore al proyecto. Las reglas marcadas como **obligatorio** no se negocian: si una tarea parece exigir saltárselas, para y pregunta.

## Qué es Dentasoft

SaaS multi-clínica de gestión para clínicas dentales: pacientes, agenda, odontograma 3D, catálogo y planificación de tratamientos, presupuestos, documentos, firma en tablet y notificaciones por email y WhatsApp. Trata **datos de salud** (categoría especial del RGPD): la seguridad y el aislamiento entre clínicas son requisitos funcionales, no extras.

## Antes de empezar cualquier trabajo

1. Lee `docs/plan/README.md` y el fichero del módulo de la tarea (`docs/plan/Mxx-*.md`).
2. Trabaja **una tarea cada vez**, identificada por su ID (`M04-T02`). No adelantes trabajo de otras tareas.
3. Si la tarea no está clara o contradice un ADR (`docs/adr/`), pregunta antes de escribir código.

## Stack

| Capa | Tecnología |
| --- | --- |
| Backend | Java 25, Spring Boot 4, Spring Modulith, Spring Security (OAuth2 Resource Server), Spring Data JPA, Flyway, Maven Wrapper |
| Base de datos | PostgreSQL (imagen con `pgvector`), Row Level Security por clínica |
| Identidad | Keycloak (realm `dentasoft`, organizaciones = clínicas) |
| Caché y colas | Redis |
| Ficheros | S3 compatible (MinIO en local) con AWS SDK v2 |
| Email | SMTP: Mailpit en local, Resend en pruebas y producción |
| WhatsApp | WhatsApp Cloud API de Meta |
| Frontend | Angular 22 (standalone + signals), PrimeNG, Transloco (i18n), pnpm |
| Tablet de firma | Expo (React Native) en `mobile/` |
| Contenedores | Docker y Docker Compose, preparados para Kubernetes |

## Estructura del repositorio

```text
backend/    Spring Boot (paquete raíz es.crisbr.dentasoft)
frontend/   Angular (src/app/core, shared, api, features)
mobile/     App Expo de firma en tablet
infra/      Docker Compose, Keycloak, PostgreSQL
docs/adr/   Decisiones de arquitectura
docs/plan/  Plan por módulos y tareas
scripts/    Utilidades (sincronizar tareas con GitHub Issues…)
```

## Comandos (Windows, PowerShell)

```powershell
# Servicios locales
cd infra; docker compose -f compose.yml -f compose.local.yml up -d

# Backend
cd backend; .\mvnw.cmd verify          # compila y pasa todos los tests
cd backend; .\mvnw.cmd spring-boot:run  # arranca en http://localhost:8080

# Frontend
cd frontend; pnpm install; pnpm start   # http://localhost:4200
cd frontend; pnpm lint; pnpm test --watch=false; pnpm build
cd frontend; pnpm api:gen               # regenera el cliente desde el OpenAPI del backend
```

## Reglas obligatorias

### Multi-clínica y acceso a datos

- **Obligatorio**: toda tabla de negocio tiene `tenant_id uuid NOT NULL`, índice que empieza por `tenant_id`, `ENABLE` + `FORCE ROW LEVEL SECURITY` y la política `tenant_isolation`. Las entidades extienden `TenantScopedEntity`.
- **Obligatorio**: la clínica activa sale del `TenantContext`, nunca de un parámetro, cuerpo de petición o ruta sin validar contra la membresía del usuario.
- **Obligatorio**: cada módulo con datos tiene un test de aislamiento (la clínica A no lee ni modifica datos de la clínica B) con Testcontainers.
- Los procesos sin usuario (jobs, webhooks) fijan la clínica explícitamente antes de tocar datos.

### Seguridad

- **Obligatorio**: nada de SQL concatenado. JPA, Spring Data o `JdbcClient` con parámetros.
- **Obligatorio**: validación con Bean Validation en todos los DTO de entrada; autorización con `@PreAuthorize` en los servicios.
- **Obligatorio**: ningún secreto en el código, en tests ni en ejemplos. Solo variables de entorno; en el repo, solo `.env.example` con valores falsos.
- **Obligatorio**: nunca uses datos reales de pacientes, ni siquiera en local. Datos de prueba inventados.
- **Obligatorio**: los logs no contienen datos personales ni clínicos (nombres, teléfonos, diagnósticos, notas). Usa identificadores.
- Errores hacia el cliente con `ProblemDetail`, sin trazas ni mensajes internos.
- No añadas dependencias nuevas (Maven o npm) sin justificarlo en el PR. Revisa licencia, mantenimiento y vulnerabilidades.
- Ficheros subidos: tipo real comprobado (Apache Tika), límite de tamaño, antivirus y descarga solo con URL firmada.
- Referencia de riesgos: OWASP Top 10:2025 y OWASP ASVS (ver la guía del proyecto).

### Datos

- Identificadores UUID v7 generados en la aplicación.
- Fechas con `timestamptz` (`OffsetDateTime`/`Instant` en Java); zona por defecto de la clínica, `Europe/Madrid`.
- Importes en `numeric(12,2)` (`BigDecimal`), nunca `double`.
- Columnas de auditoría `created_at`, `created_by`, `updated_at`, `updated_by`; bloqueo optimista con `@Version` en lo editable.
- Borrado lógico (`deleted_at`) en pacientes y datos clínicos. Nunca `DELETE` físico de datos clínicos.
- Lo clínico no se sobrescribe: el odontograma es un historial de `tooth_finding`; las notas cerradas se corrigen con adendas.
- Numeración dental **FDI (ISO 3950)**: permanentes 11–48, temporales 51–85. Superficies M, D, O/I, V, L/P.
- Nombres alineados con HL7 FHIR cuando exista un recurso equivalente (Patient, Appointment, CarePlan, Procedure…).
- **Migraciones Flyway**: `V{NNN}__descripcion.sql` en `backend/src/main/resources/db/migration`. **Nunca edites una migración ya fusionada en `main`**: crea otra.

### Backend

- Un paquete por módulo bajo `es.crisbr.dentasoft` (`tenancy`, `identity`, `patients`, `documents`, `scheduling`, `notifications`, `treatments`, `clinical`, `quotes`, `signatures`, `shared`). Solo es público lo que está en la raíz del paquete; lo demás va en subpaquetes `internal`, `web`, `persistence`.
- Los módulos se comunican por **eventos** (`ApplicationEventPublisher` + `@ApplicationModuleListener`) o por la API pública del otro módulo, nunca por sus clases internas. `ModularityTests` lo verifica.
- DTO como `record`; conversión con MapStruct; controladores finos, lógica en servicios.
- API REST bajo `/api/v1/...`, documentada con springdoc (OpenAPI). Paginación con `page`, `size`, `sort`.
- Textos de cara al usuario en el backend (emails, PDF) desde ficheros de mensajes, nunca literales en el código.

### Frontend

- Componentes standalone, `ChangeDetectionStrategy.OnPush`, signals para el estado.
- Una carpeta por módulo funcional en `src/app/features/` con carga diferida.
- **Obligatorio**: ningún texto visible escrito a mano en plantillas o componentes: todo con Transloco (`src/assets/i18n/es.json`), aunque de momento solo haya español.
- El cliente de la API se genera con `pnpm api:gen`; no escribas a mano DTO que ya existen en el backend.
- Componentes de UI de PrimeNG; no mezcles otras librerías de componentes.
- La seguridad real está en el backend: ocultar un botón no es control de acceso.

### Tests

- Cada tarea entrega sus tests. Unitarios para la lógica, integración con Testcontainers para repositorios, seguridad y RLS.
- Un bug corregido lleva un test que lo reproduce.

## Flujo de trabajo con GitHub Issues

Cada tarea del plan tiene un issue en GitHub (título `Mxx-Tyy · …`, etiqueta `modulo:Mxx`, milestone del módulo) y aparece en el Project **Dentasoft MVP**. **Obligatorio**: todo trabajo parte de un issue y termina cerrándolo con un pull request. Se usa GitHub CLI (`gh`).

1. **Elegir la tarea**: la siguiente abierta del módulo en curso cuyas dependencias estén cerradas.
   ```powershell
   gh issue list --label modulo:M04 --state open
   gh issue view 12
   ```
   Si no hay issue para lo que se va a hacer, se crea antes (y se añade la tarea al fichero del módulo en `docs/plan/`).
2. **Tomarla**: `gh issue edit 12 --add-assignee "@me"`.
3. **Rama** con el número del issue y el ID de la tarea: `feature/12-M04-T02-busqueda-pacientes` (o `fix/…`, `chore/…`).
4. **Pull request en borrador al empezar**, con `Closes #12` en la descripción. Así el Project mueve la tarjeta a «En curso» automáticamente.
   ```powershell
   gh pr create --draft --title "feat(patients): búsqueda de pacientes [M04-T02]" --body "Closes #12"
   ```
5. **Commits** con Conventional Commits y el ID de la tarea: `feat(patients): búsqueda por nombre y teléfono [M04-T02]`.
6. **Decisiones, bloqueos o cambios de alcance**: se dejan como comentario en el issue (`gh issue comment 12 --body "…"`), no solo en el chat.
7. **Trabajo nuevo descubierto** (un bug, algo que falta): issue nuevo con `gh issue create`, etiqueta del módulo y referencia al issue original. No se mezcla en el PR actual.
8. **Terminar**: marcar el PR como listo (`gh pr ready`), CI en verde y, en el mismo PR, cambiar el **Estado** de la tarea a `hecho` en `docs/plan/`. Al fusionar, el issue se cierra solo y la tarjeta pasa a «Hecho». Si hubo una decisión de arquitectura, añadir un ADR.

## Definición de terminado

Una tarea está terminada cuando:

- Cumple todos sus criterios de aceptación.
- Tiene tests y la CI está en verde (`mvnw verify`, `pnpm lint`, `pnpm test`, `pnpm build`).
- Respeta las reglas obligatorias de este fichero (multi-clínica, seguridad, i18n).
- La migración, si la hay, es nueva y reversible a mano o documentada.
- La documentación afectada (plan, ADR, README) está actualizada.
