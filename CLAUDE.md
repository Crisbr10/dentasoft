# Dentasoft — instrucciones para Claude Code

@AGENTS.md

## Notas para Claude Code

- Para trabajar una tarea usa la skill `/tarea <Mxx-Tyy>` (o `/tarea siguiente`), y para cerrarla `/terminar-tarea`. Si el usuario pide una tarea sin usar la skill, sigue igualmente sus pasos.
- Sigue siempre el «Flujo de trabajo con GitHub Issues» de `AGENTS.md`: empieza leyendo el issue con `gh issue view`, trabaja en una rama con su número y abre el PR en borrador con `Closes #N` desde el principio.
- En este repositorio, los issues y los pull requests se crean solo con el flujo de `AGENTS.md` y las skills del proyecto `/tarea` y `/terminar-tarea`. No uses las skills globales `branch-pr` ni `issue-creation`, que siguen otro formato.
- Si `gh` no está autenticado o no hay issue para la tarea, avisa antes de empezar en lugar de trabajar sin issue.
- Antes de programar una tarea, resume en 3–5 líneas qué vas a cambiar y espera confirmación si la tarea toca seguridad, multi-clínica o migraciones.
- No ejecutes comandos que borren datos (`docker compose down -v`, borrado de volúmenes, `git reset --hard`) sin pedir permiso.
- Puedes hacer `git push` de la rama de la tarea y crear o actualizar su pull request en borrador. Nunca hagas push a `main`, ni fusiones PR, ni cierres issues a mano: eso lo decide Cristhian.
