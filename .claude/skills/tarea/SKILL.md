---
name: tarea
description: Empieza y desarrolla una tarea del plan de Dentasoft (ID Mxx-Tyy) siguiendo el flujo con GitHub Issues. Úsala cuando el usuario diga "/tarea M04-T02", "vamos con la tarea…" o "siguiente tarea".
argument-hint: "[Mxx-Tyy | siguiente]"
---

# Desarrollar la tarea $ARGUMENTS

Sigue estos pasos en orden. No te saltes ninguno y no escribas código hasta el paso 5.

## 1. Comprobaciones previas

- `gh auth status`: si no está autenticado, para y pide al usuario que ejecute `gh auth login`.
- `git status`: si hay cambios sin guardar que no son de esta tarea, para y pregunta.
- `git switch main` y `git pull` para partir de lo último.

## 2. Localizar el issue

- Si el argumento es un ID (`M04-T02`): `gh issue list --search "<ID> in:title" --state all`.
- Si el argumento es `siguiente` o está vacío: abre `docs/plan/README.md`, toma el primer módulo no terminado y, dentro de él, la primera tarea con estado `pendiente` cuyas dependencias estén en `hecho`. Confírmala con el usuario antes de seguir.
- Si no existe issue para la tarea, avisa: se crea con `.\scripts\sync-issues.ps1 -Modulo Mxx` o, si la tarea no está en el plan, primero se añade al fichero del módulo.
- Si el issue está cerrado, avisa y no sigas.

## 3. Entender la tarea

- `gh issue view <N>` y lee la tarea completa en su fichero `docs/plan/Mxx-*.md` (objetivo del módulo, modelo de datos, dependencias).
- Comprueba que sus dependencias (`Depende de`) están cerradas. Si alguna no lo está, para y dilo.
- Relee las reglas obligatorias de `AGENTS.md` que apliquen (multi-clínica, seguridad, datos, i18n).

## 4. Tomar la tarea

```powershell
gh issue edit <N> --add-assignee "@me"
git switch -c feature/<N>-<ID>-<descripcion-corta>
git commit --allow-empty -m "chore: inicio de <ID>"
git push -u origin HEAD
gh pr create --draft --title "<tipo>(<modulo>): <título> [<ID>]" --body "Closes #<N>`n`nTarea <ID>. Ver docs/plan/<fichero>."
```

Usa `fix/` o `chore/` en lugar de `feature/` si corresponde.

## 5. Plan y confirmación

Antes de programar, escribe al usuario un plan de 3 a 6 líneas: qué ficheros vas a crear o cambiar, qué migraciones, qué tests y cómo cubrirás cada criterio de aceptación. **Espera su confirmación** si la tarea toca seguridad, multi-clínica, migraciones o datos de pacientes; en el resto, continúa salvo que el usuario haya pedido revisar siempre.

## 6. Desarrollo

- Commits pequeños con Conventional Commits y el ID: `feat(patients): búsqueda por teléfono [M04-T02]`.
- Tests junto al código: unitarios, integración con Testcontainers y test de aislamiento entre clínicas si hay datos.
- Decisiones, bloqueos o cambios de alcance: coméntalos en el issue (`gh issue comment <N> --body "..."`).
- Trabajo nuevo que no es de esta tarea: crea otro issue con `gh issue create` (etiqueta `modulo:Mxx` y `tipo:tarea` o `tipo:bug`) y no lo mezcles aquí.
- `git push` de la rama con frecuencia.

## 7. Verificación

Ejecuta lo que aplique y no des la tarea por terminada si algo falla:

```powershell
cd backend; .\mvnw.cmd verify
cd frontend; pnpm lint; pnpm test --watch=false; pnpm build
```

Repasa cada criterio de aceptación del issue y la definición de terminado de `AGENTS.md`.

## 8. Cierre

Cuando todo esté verde, usa la skill `/terminar-tarea`.
