# M00 · Fundamentos técnicos

**Objetivo**: dejar el monorepo con backend, frontend e infraestructura local funcionando, calidad y CI desde el primer commit, para que los módulos funcionales solo añadan negocio.
**Depende de**: —
**Resultado**: `docker compose up` levanta los servicios, el backend arranca y responde `/actuator/health`, el frontend compila y la CI está en verde.

## Alcance

- Repositorio base, reglas para agentes, plan y plantillas de GitHub.
- Esqueleto de backend (Spring Boot 4, Spring Modulith) y de frontend (Angular 22, PrimeNG, Transloco).
- Docker Compose local: PostgreSQL con pgvector, Keycloak, Redis, MinIO, Mailpit y ClamAV.
- Calidad (formato, cobertura, tests) y CI en GitHub Actions.
- Base de seguridad transversal: cabeceras, CORS, errores, logs y límite de peticiones.

## Fuera de alcance

- Lógica de negocio, Keycloak configurado a fondo (M02) y despliegue a pruebas (M03).

## Tareas

### M00-T01 · Base del repositorio y documentación

- **Estado**: hecho
- **Depende de**: —
- **Descripción**: estructura de carpetas, ficheros base (`.gitattributes`, `.gitignore`, `.editorconfig`, `LICENSE`, `README`), `AGENTS.md`, `CLAUDE.md`, ADR iniciales, plan por módulos, plantillas de issue y PR, y script de sincronización con GitHub Issues.
- **Criterios de aceptación**:
  - [x] Carpetas `backend/`, `frontend/`, `mobile/`, `infra/`, `docs/`, `scripts/`, `.github/`
  - [x] `AGENTS.md` con reglas obligatorias y definición de terminado
  - [x] ADR 0001–0005 y plan M00–M13 con tareas
  - [x] Plantillas de issue y pull request
  - [x] Primer commit en `main` subido a GitHub
  - [x] Issues creados con `scripts/sync-issues.ps1` y un Project de GitHub con el tablero

### M00-T02 · Esqueleto del backend

- **Estado**: pendiente
- **Depende de**: M00-T01
- **Descripción**: generar el proyecto Spring Boot 4 con Java 25 (dependencias de la guía), paquete raíz `es.crisbr.dentasoft`, Spring Modulith con `ModularityTests`, Actuator, configuración por variables de entorno con valores locales por defecto, hilos virtuales y apagado ordenado.
- **Criterios de aceptación**:
  - [ ] `.\mvnw.cmd verify` en verde
  - [ ] `ModularityTests` verifica los módulos y genera su documentación
  - [ ] `application.yml` sin secretos; perfil `local`
  - [ ] Manejador global de errores con `ProblemDetail`
  - [ ] springdoc expone `/v3/api-docs` y Swagger UI solo en perfiles no productivos
- **Reparto de dependencias del backend**: cada dependencia entra con la tarea que la usa por primera vez.

  | Tarea | Dependencias |
  | --- | --- |
  | M00-T02 | Web, Actuator, Validation, Spring Modulith, springdoc-openapi y Configuration Processor |
  | M00-T06 | Testcontainers (PostgreSQL y Redis), Spotless y JaCoCo |
  | M00-T08 | Spring Data Redis y Bucket4j |
  | M01-T01 | Spring Data JPA, PostgreSQL y Flyway (con `flyway-database-postgresql`) |
  | M02-T02 | Spring Security y OAuth2 Resource Server |
  | M05 | AWS SDK v2 S3, Apache Tika, OpenHTMLtoPDF y Thymeleaf |
  | M07-T02 | Spring Mail |

  No se usa `spring-boot-docker-compose`: los servicios se levantan a mano desde `infra/`.

### M00-T03 · Infraestructura local con Docker Compose

- **Estado**: pendiente
- **Depende de**: M00-T01
- **Descripción**: `infra/compose.yml` y `infra/compose.local.yml` con PostgreSQL (`pgvector/pgvector`), roles `dentasoft_owner` y `dentasoft_app`, Keycloak, Redis, MinIO con bucket `dentasoft-docs` versionado, Mailpit y ClamAV. Versiones fijadas en `.env.example`.
- **Criterios de aceptación**:
  - [ ] `docker compose -f compose.yml -f compose.local.yml up -d` deja todos los servicios sanos
  - [ ] Script de init crea bases, roles y la extensión `vector`
  - [ ] `.env.example` sin valores reales y `.env` ignorado por Git
  - [ ] Límites de memoria por servicio
  - [ ] Tabla de URLs locales en `infra/README.md`

### M00-T04 · Imagen Docker del backend

- **Estado**: pendiente
- **Depende de**: M00-T02
- **Descripción**: `backend/Dockerfile` multi-etapa con capas de Spring Boot, JRE 25, usuario sin privilegios y health check; servicio `app` opcional en Compose (perfil `app`) para probar la app en contenedor.
- **Criterios de aceptación**:
  - [ ] La imagen arranca contra la infraestructura local y responde `/actuator/health/readiness`
  - [ ] El proceso corre con UID distinto de 0
  - [ ] Trivy no reporta vulnerabilidades críticas

### M00-T05 · Esqueleto del frontend

- **Estado**: pendiente
- **Depende de**: M00-T01
- **Descripción**: Angular 22 sin SSR con pnpm, PrimeNG (tema y tokens de diseño), Transloco con `es.json`, ESLint y Prettier, estructura `core/shared/api/features`, layout base (barra lateral, cabecera, área de contenido) y página de inicio que llama a `/actuator/health`.
- **Criterios de aceptación**:
  - [ ] `pnpm lint`, `pnpm test --watch=false` y `pnpm build` en verde
  - [ ] Ningún texto visible sin Transloco
  - [ ] `.nvmrc` con la versión de Node
  - [ ] `vercel.json` con reescrituras de SPA y cabeceras de seguridad

### M00-T06 · Calidad y tests base

- **Estado**: pendiente
- **Depende de**: M00-T02
- **Descripción**: Spotless (formato), JaCoCo (cobertura), clase base de integración con Testcontainers (PostgreSQL, Redis) y datos de prueba inventados; scripts `api:gen` (ng-openapi-gen) y formato en el frontend.
- **Criterios de aceptación**:
  - [ ] `mvnw verify` falla si el formato no es correcto
  - [ ] Test de integración de ejemplo usando Testcontainers
  - [ ] `pnpm api:gen` genera el cliente desde el backend local

### M00-T07 · Integración continua

- **Estado**: pendiente
- **Depende de**: M00-T04, M00-T05
- **Descripción**: workflow `ci.yml` con filtro por rutas (backend, frontend), build de imagen en `main`, escaneo de secretos (gitleaks), CodeQL, Dependabot y Trivy; protección de la rama `main`.
- **Criterios de aceptación**:
  - [ ] Un PR que solo toca el frontend no compila el backend, y no queda bloqueado por checks obligatorios
  - [ ] La imagen se publica en GHCR con la etiqueta del commit al fusionar en `main`
  - [ ] `main` exige PR y CI en verde
  - [ ] *Actions* fijadas por hash

### M00-T08 · Base de seguridad transversal

- **Estado**: pendiente
- **Depende de**: M00-T02
- **Descripción**: cabeceras de seguridad, CORS por variable de entorno, logs en JSON sin datos personales (con ID de petición), límite de peticiones por IP y por usuario con Bucket4j sobre Redis, y tamaño máximo de petición.
- **Criterios de aceptación**:
  - [ ] Tests de las cabeceras y de CORS
  - [ ] Superar el límite devuelve 429 con `ProblemDetail`
  - [ ] Cada línea de log lleva el ID de petición y ningún dato personal
