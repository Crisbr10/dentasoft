# M00 · Fundamentos técnicos

**Objetivo**: dejar el monorepo con backend, frontend e infraestructura local funcionando, calidad y CI desde el primer commit, para que los módulos funcionales solo añadan negocio.
**Depende de**: —
**Resultado**: `docker compose up` levanta los servicios, el backend arranca y responde `/actuator/health`, el frontend compila y la CI está en verde.

## Alcance

- Repositorio base, reglas para agentes, plan y plantillas de GitHub.
- Esqueleto de backend (Spring Boot 4, Spring Modulith) y de frontend (Angular 22, Angular Material, Transloco).
- Docker Compose local: PostgreSQL con pgvector, Keycloak, Redis, almacenamiento S3 (SeaweedFS), Mailpit y ClamAV.
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

- **Estado**: hecho
- **Depende de**: M00-T01
- **Descripción**: generar el proyecto Spring Boot 4 con Java 25 (dependencias de la guía), paquete raíz `es.crisbr.dentasoft`, Spring Modulith con `ModularityTests`, Actuator, configuración por variables de entorno con valores locales por defecto, hilos virtuales y apagado ordenado.
- **Criterios de aceptación**:
  - [x] `.\mvnw.cmd verify` en verde
  - [x] `ModularityTests` verifica los módulos y genera su documentación
  - [x] `application.yml` sin secretos; perfil `local`
  - [x] Manejador global de errores con `ProblemDetail`
  - [x] springdoc expone `/v3/api-docs` y Swagger UI solo en perfiles no productivos
  - [x] Ningún dominio ni URL pública en el código ni en `application*.yml` fuera del perfil `local`; un test falla si encuentra `crisbr.es` o una URL `https://` fija en `backend/src/main`
  - [x] `dentasoft.urls` (`app`, `api`, `auth`) como record validado; valores por defecto solo en `local` y obligatorias por variables de entorno en el resto: sin ellas la app no arranca y el error dice cuál falta
  - [x] `dentasoft.cors.allowed-origins` preparada para M00-T08, por defecto `dentasoft.urls.app`; la URL del servidor en OpenAPI sale de `dentasoft.urls.api`
- **URLs públicas** (requisito añadido el 2026-11-23: el dominio actual, `crisbr.es`, es temporal y cambiará):

  | Variable | Propiedad | Valor en `local` |
  | --- | --- | --- |
  | `DENTASOFT_URLS_APP` | `dentasoft.urls.app` (frontend) | `http://localhost:4200` |
  | `DENTASOFT_URLS_API` | `dentasoft.urls.api` (backend) | `http://localhost:8080` |
  | `DENTASOFT_URLS_AUTH` | `dentasoft.urls.auth` (Keycloak) | `http://localhost:8180` |
  | `DENTASOFT_CORS_ALLOWED_ORIGINS` | `dentasoft.cors.allowed-origins` (lista separada por comas) | `dentasoft.urls.app` |

- **Reparto de dependencias del backend**: cada dependencia entra con la tarea que la usa por primera vez.

  | Tarea | Dependencias |
  | --- | --- |
  | M00-T02 | Web, Actuator, Validation, Spring Modulith, springdoc-openapi y Configuration Processor |
  | M00-T06 | Testcontainers (PostgreSQL), Spotless y JaCoCo |
  | M00-T08 | Spring Data Redis, Bucket4j y Testcontainers (Redis, adelantado de M00-T06 para probar el límite de peticiones) |
  | M01-T01 | Spring Data JPA, PostgreSQL y Flyway (con `flyway-database-postgresql`) |
  | M02-T02 | Spring Security y OAuth2 Resource Server |
  | M05 | AWS SDK v2 S3, Apache Tika, OpenHTMLtoPDF y Thymeleaf |
  | M07-T02 | Spring Mail |

  No se usa `spring-boot-docker-compose`: los servicios se levantan a mano desde `infra/`.

### M00-T03 · Infraestructura local con Docker Compose

- **Estado**: hecho
- **Depende de**: M00-T01
- **Descripción**: `infra/compose.yml` y `infra/compose.local.yml` con PostgreSQL (`pgvector/pgvector`), roles `dentasoft_owner` y `dentasoft_app`, Keycloak, Redis, almacenamiento S3 con bucket `dentasoft-docs` versionado, Mailpit y ClamAV (perfil opcional `antivirus`). Versiones fijadas en `.env.example`. El S3 local es SeaweedFS en lugar de MinIO, que dejó de publicar imágenes (ADR 0007).
- **Criterios de aceptación**:
  - [x] `docker compose -f compose.yml -f compose.local.yml up -d --wait` deja todos los servicios sanos
  - [x] Script de init crea bases, roles y la extensión `vector`
  - [x] `.env.example` sin valores reales y `.env` ignorado por Git
  - [x] Límites de memoria por servicio
  - [x] Tabla de URLs locales en `infra/README.md`

### M00-T04 · Imagen Docker del backend

- **Estado**: hecho
- **Depende de**: M00-T02
- **Descripción**: `backend/Dockerfile` multi-etapa con capas de Spring Boot, JRE 25, usuario sin privilegios y health check; servicio `app` opcional en Compose (perfil `app`) para probar la app en contenedor.
- **Criterios de aceptación**:
  - [x] La imagen arranca contra la infraestructura local y responde `/actuator/health/readiness`
  - [x] El proceso corre con UID distinto de 0
  - [x] Trivy no reporta vulnerabilidades críticas

### M00-T05 · Esqueleto del frontend

- **Estado**: hecho
- **Depende de**: M00-T01
- **Descripción**: Angular 22 sin SSR con pnpm, Angular Material y CDK (tema y tokens de diseño, ADR 0008), Transloco con `es.json`, ESLint y Prettier, estructura `core/shared/api/features`, layout base (barra lateral, cabecera, área de contenido) y página de inicio que llama a `/actuator/health`. Las URL de la API y de Keycloak no van escritas en el código: se generan en el build desde variables de entorno (`DENTASOFT_URLS_API`, `DENTASOFT_URLS_AUTH`), con `localhost` solo para desarrollo (ADR 0006).
- **Criterios de aceptación**:
  - [x] `pnpm lint`, `pnpm test --watch=false` y `pnpm build` en verde
  - [x] Ningún texto visible sin Transloco
  - [x] `.nvmrc` con la versión de Node
  - [x] `vercel.json` con reescrituras de SPA y cabeceras de seguridad
  - [x] Ningún dominio escrito en `src/`; un build con otras variables apunta a otro dominio sin tocar código

### M00-T06 · Calidad y tests base

- **Estado**: hecho
- **Depende de**: M00-T02
- **Descripción**: Spotless (formato), JaCoCo (cobertura), clase base de integración con Testcontainers (PostgreSQL, Redis; el contenedor de Redis ya existe desde M00-T08 en `TestRedisConfiguration`) y datos de prueba inventados; scripts `api:gen` (ng-openapi-gen) y formato en el frontend. La base es la anotación `@IntegrationTest`. Su PostgreSQL usa la imagen y el script de init de `infra/`, y el driver queda en scope test hasta que M01-T01 añada JDBC, Flyway y `@ServiceConnection`. El cliente generado (`src/app/api`) va en el repositorio sin ninguna URL escrita: la URL base sale de `config.json`.
- **Criterios de aceptación**:
  - [x] `mvnw verify` falla si el formato no es correcto
  - [x] Test de integración de ejemplo usando Testcontainers
  - [x] `pnpm api:gen` genera el cliente desde el backend local

### M00-T07 · Integración continua

- **Estado**: hecho
- **Depende de**: M00-T04, M00-T05
- **Descripción**: workflow `ci.yml` con filtro por rutas (backend, frontend), build de imagen en `main`, escaneo de secretos (gitleaks), CodeQL, Dependabot y Trivy; protección de la rama `main`.
- **Criterios de aceptación**:
  - [x] Un PR que solo toca el frontend no compila el backend, y no queda bloqueado por checks obligatorios
  - [x] La imagen se publica en GHCR con la etiqueta del commit al fusionar en `main`
  - [x] `main` exige PR y CI en verde
  - [x] *Actions* fijadas por hash

### M00-T08 · Base de seguridad transversal

- **Estado**: hecho
- **Depende de**: M00-T02
- **Descripción**: cabeceras de seguridad, CORS por variable de entorno, logs en JSON sin datos personales (con ID de petición), límite de peticiones por IP y por usuario con Bucket4j sobre Redis, y tamaño máximo de petición.
- **Criterios de aceptación**:
  - [x] Tests de las cabeceras y de CORS
  - [x] Superar el límite devuelve 429 con `ProblemDetail`
  - [x] Cada línea de log lleva el ID de petición y ningún dato personal

### M00-T09 · Ajustes de la revisión de M00-T02

- **Estado**: hecho
- **Depende de**: M00-T02
- **Descripción**: hallazgos informativos de la revisión de Gentle AI del PR #87 (M00-T02). Refuerzo de la comprobación de dominios fijos, tests que faltan en el manejador de errores y en la configuración, y un comentario que exagera lo que garantiza. El log de los errores 500 con la excepción completa queda fuera: es de M00-T08.
- **Criterios de aceptación**:
  - [x] `NoHardcodedUrlsTests` también falla con una URL `http://` fija en `backend/src/main`, salvo `localhost` en `application-local.yml`
  - [x] Test de `ConstraintViolationException` con violaciones reales: `errors` lleva el nombre del campo (último nodo de la ruta) y nunca el valor rechazado
  - [x] El test de URL no válida comprueba que falla `dentasoft.urls.app` por la restricción de URL, no cualquier error de `dentasoft.urls`
  - [x] Test de un origen CORS no válido en `DENTASOFT_CORS_ALLOWED_ORIGINS`: la app no arranca
  - [x] El comentario de `application-prod.yml` no promete que otro perfil no pueda cambiar esos valores

### M00-T10 · Script para arrancar el backend en local

- **Estado**: hecho
- **Depende de**: M00-T03
- **Descripción**: `mvnw spring-boot:run` no arranca sin las contraseñas y el secreto de Keycloak de `infra/.env`, y algunas variables tienen en el backend otro nombre que en `.env`. Un script `scripts/run-backend.ps1` carga `infra/.env` solo en su proceso, traduce los nombres a los que espera el backend y lanza `mvnw spring-boot:run`.
- **Criterios de aceptación**:
  - [x] `scripts/run-backend.ps1` lee `infra/.env` y fija las variables solo en el entorno de su proceso: no las escribe en ningún fichero ni en el entorno del usuario o de la máquina, y no las muestra en pantalla ni en el log
  - [x] Traduce las variables de `infra/.env` a los nombres que espera el backend, con la misma correspondencia que el servicio `app` de `infra/compose.local.yml` (por ejemplo, `DENTASOFT_KC_BACKEND_CLIENT_SECRET` → `DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET`)
  - [x] Lanza `backend\mvnw.cmd spring-boot:run` con el perfil local y devuelve su código de salida
  - [x] Si falta `infra/.env` o una variable obligatoria, falla con un mensaje que dice cuál falta, sin mostrar ningún valor
  - [x] Documentado en `backend/README.md`
