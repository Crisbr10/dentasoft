---
name: terminar-tarea
description: Cierra la tarea de Dentasoft en curso en la rama actual: verifica criterios, actualiza el plan y deja el pull request listo para revisión. Úsala cuando el usuario diga "/terminar-tarea" o "da por terminada la tarea".
---

# Terminar la tarea en curso

1. **Identificar la tarea**: de la rama actual (`feature/<N>-<ID>-…`) saca el número de issue y el ID. Comprueba con `gh pr view` que hay un PR abierto con `Closes #<N>`.
2. **Verificar** (todo debe estar en verde):
   - Backend: `cd backend; .\mvnw.cmd verify`
   - Frontend (si se tocó): `cd frontend; pnpm lint; pnpm test --watch=false; pnpm build`
3. **Criterios de aceptación**: recorre cada criterio del issue y di cómo se cumple (test, endpoint, pantalla). Si alguno no se cumple, vuelve a desarrollarlo; no cierres la tarea a medias.
4. **Revisión de seguridad rápida** sobre el diff (`git diff main...HEAD`):
   - Tablas nuevas con `tenant_id`, RLS y test de aislamiento.
   - Sin SQL concatenado; DTO validados; `@PreAuthorize` en servicios.
   - Sin secretos, datos reales ni datos personales en logs.
   - Textos de interfaz con Transloco.
   - Migraciones nuevas, ninguna editada.
5. **Actualizar documentación en la misma rama**:
   - En `docs/plan/Mxx-*.md`: `**Estado**: hecho` y criterios marcados `[x]`.
   - Si era la última tarea del módulo: estado del módulo en `docs/plan/README.md`.
   - ADR nuevo si se tomó una decisión de arquitectura; `AGENTS.md` si cambian comandos o reglas.
6. **Commit y push**: `docs(plan): <ID> terminada [<ID>]` y `git push`.
7. **PR listo para revisión**:
   - Completa la descripción con la plantilla de `.github/pull_request_template.md` (resumen y casillas marcadas).
   - `gh pr ready`
   - `gh pr checks --watch` hasta que la CI termine.
8. **Informar al usuario**: enlace al PR, resumen de 3 líneas y cualquier cosa que deba revisar con atención. **No fusiones el PR ni cierres el issue**: lo hace el usuario; el issue se cierra solo al fusionar.
