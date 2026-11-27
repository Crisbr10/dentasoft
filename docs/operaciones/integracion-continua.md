# Integración continua

La CI corre en GitHub Actions. Este documento explica qué comprueba, qué bloquea una fusión en `main` y cómo se mantiene.

## Workflows

| Fichero | Cuándo | Qué hace |
| --- | --- | --- |
| [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) | Cada PR y cada push a `main` | Filtro por rutas, gitleaks, backend, frontend, scripts, `ci-ok` y, en `main`, la imagen |
| [`.github/workflows/codeql.yml`](../../.github/workflows/codeql.yml) | Cada PR, cada push a `main` y los lunes | CodeQL de Java y TypeScript (`security-extended`) |
| [`.github/dependabot.yml`](../../.github/dependabot.yml) | Los lunes | Actualizaciones de Actions, Maven, npm y las imágenes base del Dockerfile |

### Jobs de `ci.yml`

- **`changes`**: decide qué partes cambiaron. `backend` corre si cambia `backend/**`; `frontend`, si cambia `frontend/**`; `scripts`, si cambia `scripts/**`. Los tres corren si cambia el propio `ci.yml`.
- **`secrets`**: gitleaks sobre **todo** el historial, en cada ejecución. Tarda segundos y también detecta un secreto que se añadió y se quitó dentro del mismo PR.
- **`backend`**: `mvnw verify` (compilación, tests unitarios y de integración con Testcontainers, y formato).
- **`frontend`**: `pnpm install --frozen-lockfile`, `pnpm audit --audit-level high`, `format:check`, `lint`, `test` y `build`. El build usa dominios reservados `.test` (ADR 0006).
- **`scripts`**: los tests de Pester de `scripts/` (todos los `*.Tests.ps1`), en Windows con Windows PowerShell 5.1, como se ejecutan en local. Usa Pester 3.4.0, el que trae Windows y para el que está escrita la sintaxis de los tests (`Should Be`); lo importa por versión porque la imagen del runner puede traer también Pester 5, que no la admite. Falla si falla algún test o si no encuentra ninguno. Los tests usan ficheros `.env` de prueba: no leen `infra/.env` ni necesitan secretos. En local: `Invoke-Pester .\scripts`.
- **`image`**: si cambió el backend, construye la imagen y la escanea con Trivy. En un PR no publica nada: así un PR que rompe la imagen (por ejemplo, una actualización de Dependabot de la imagen base) falla antes de fusionarse. En push a `main`, además, la publica en GHCR.
- **`ci-ok`**: el **único check obligatorio**. Corre siempre y falla si algún job del que depende ha fallado o se ha cancelado. Los jobs saltados por el filtro no lo bloquean, así que un PR que solo toca el frontend no espera al backend.

## Imagen del backend

Al fusionar en `main` un cambio del backend, se publica `ghcr.io/<propietario>/dentasoft-backend:<sha-del-commit>`. Solo se usa la etiqueta del commit: nunca se reutiliza, así que un despliegue sabe exactamente qué código ejecuta. No hay `latest`.

Trivy sigue la misma política que en local ([backend/README.md](../../backend/README.md#escaneo-de-vulnerabilidades)): las vulnerabilidades críticas impiden publicar la imagen y las altas se listan en el log para revisarlas.

El escaneo también corre en los PR que tocan el backend, así que una vulnerabilidad crítica nueva en la imagen base o en una dependencia bloquea esos PR aunque no tengan relación con ella. Lo normal es actualizar la imagen o la dependencia. Si no hay versión corregida, se hace una excepción temporal en [`backend/.trivyignore`](../../backend/.trivyignore): un CVE por línea, precedido de un comentario con el issue que la justifica y la fecha de revisión. Las bases de datos de Trivy se descargan una vez por ejecución, con 3 reintentos ante un fallo transitorio del registro.

## Protección de `main`

El ruleset está en [`.github/rulesets/main.json`](../../.github/rulesets/main.json) y se aplica con:

```powershell
.\scripts\configurar-github.ps1 -DryRun   # enseña qué haría
.\scripts\configurar-github.ps1
```

El ruleset exige:

- Pull request para todo cambio, fusionado solo con *squash*, y con las conversaciones resueltas. No hace falta aprobación: hoy solo hay una persona en el proyecto.
- El check `ci-ok` en verde, emitido por GitHub Actions (nadie puede falsearlo con un estado manual).
- Que CodeQL no encuentre alertas de seguridad altas o críticas.
- Sin force push ni borrado de la rama. Sin excepciones, tampoco para administradores.

El script también deja `GITHUB_TOKEN` en solo lectura por defecto (cada job pide los permisos que necesita), activa el escaneo de secretos de GitHub con protección de push y las alertas y actualizaciones de seguridad de Dependabot.

Todo esto necesita que el repositorio sea **público**: en el plan gratuito, GitHub no permite rulesets ni code scanning en repositorios privados personales. Antes de hacerlo público se pasó gitleaks por todo el historial, incluidas las ramas de los PR (`refs/pull/*`), que GitHub también expone.

## Mantenimiento

- **Actions fijadas por hash**: cada `uses:` lleva el SHA del commit y la versión en un comentario (`# v7.0.1`). Dependabot actualiza los dos. Nunca uses una etiqueta (`@v7`): se puede mover a otro código.
- **Imágenes de herramientas** (gitleaks y Trivy): fijadas por etiqueta y digest en las variables `GITLEAKS_IMAGE` y `TRIVY_IMAGE` de `ci.yml`. Dependabot no las ve; se actualizan a mano, igual que la versión de Trivy de `backend/README.md`.
- **Imágenes de `infra/`**: salen de variables de `infra/.env.example`, así que también se actualizan a mano.
- **Espera de una semana** (`cooldown`): Dependabot no propone una versión hasta 7 días después de publicarse, para dar tiempo a detectar y retirar una versión comprometida. Las actualizaciones de seguridad no esperan.
- **`pnpm audit` bloquea a propósito**: depende de la base de avisos del registro, así que un aviso alto nuevo sobre una dependencia que ya teníamos hace fallar cualquier PR que toque el frontend, aunque no tenga relación. Lo normal es actualizar la dependencia (Dependabot suele abrir ya el PR de seguridad). Si no hay versión corregida, se hace una excepción temporal añadiendo `--ignore GHSA-xxxx-xxxx-xxxx` al paso *Audit dependencies* de `ci.yml`, con un comentario que enlace el issue que la justifica y la fecha de revisión.
- **Añadir un job obligatorio**: añádelo a `needs` de `ci-ok`. No lo añadas al ruleset: el ruleset solo exige `ci-ok`.
