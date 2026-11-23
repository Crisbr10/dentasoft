# Dentasoft

SaaS de gestión para clínicas dentales: pacientes, agenda, odontograma 3D, catálogo y planificación de tratamientos, presupuestos, documentos, firma en tablet y notificaciones por email y WhatsApp. Multi-clínica, con datos de salud tratados según el RGPD.

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `backend/` | Spring Boot 4 (Java 25), monolito modular con Spring Modulith |
| `frontend/` | Angular 22 con Angular Material y Transloco, desplegado en Vercel |
| `mobile/` | App Expo de firma en tablet |
| `infra/` | Docker Compose, Keycloak, PostgreSQL |
| `docs/` | [Plan por módulos](docs/plan/README.md) y [decisiones de arquitectura](docs/adr/README.md) |
| `scripts/` | Utilidades del proyecto |

## Cómo trabajar

1. Lee [`AGENTS.md`](AGENTS.md): reglas del proyecto para personas y agentes de código.
2. Elige la siguiente tarea en el [plan](docs/plan/README.md) o en el tablero de GitHub.
3. Una rama y un pull request por tarea, con la CI en verde.

## Arranque local

Requisitos: Docker Desktop, Java 25, Node 24 y pnpm. Los comandos de arranque se documentan en `AGENTS.md` a medida que se completan las tareas de **M00 · Fundamentos**.

Código propietario. Ver [`LICENSE`](LICENSE).
