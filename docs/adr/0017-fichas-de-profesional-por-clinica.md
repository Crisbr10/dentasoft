# 0017 — Fichas de profesional por clínica

Fecha: 2026-11-27 · Estado: aceptada

## Contexto

M02-T06 crea la ficha de profesional: número de colegiado, área clínica y color en la agenda. La agenda (M06), los presupuestos (M11) y los documentos tendrán que mostrar al profesional. Había cinco cuestiones:

- **Qué es de la persona y qué de la clínica**: alguien puede trabajar en dos clínicas, con otro color o colegiado en otra provincia.
- **El nombre**: vive en Keycloak (ADR 0015). Pedirlo a Keycloak por cada fila de la agenda no escala, y la agenda dependería de que Keycloak responda.
- **Personas sin cuenta**: un colaborador externo pasa consulta, aunque no entre en la aplicación.
- **Membresías**: el comentario de V007 llama a `clinic_membership` «FHIR PractitionerRole». Pero una membresía es el acceso a la aplicación de cualquier rol, también de recepción, y no la actividad clínica.
- **Especialidades**: en España la odontología no tiene especialidades reconocidas oficialmente.

## Decisión

- **Una ficha por clínica**: la tabla `practitioner_role` (V012, entidad `PractitionerRole`), con el nombre de FHIR `PractitionerRole`.
  - No hay tabla de la persona (FHIR `Practitioner`). Cuando la persona tiene cuenta, es su `user_account` y sus datos siguen en Keycloak.
  - Una tabla de personas común a todas las clínicas sería un dato personal fuera del aislamiento por clínica.
  - Las membresías siguen siendo el acceso. La ficha es lo que el profesional hace en la clínica.
- **Nombre para mostrar en la ficha** (`display_name`):
  - Lo escribe la clínica: es como el profesional aparece en sus documentos.
  - No se copia de Keycloak ni se sincroniza.
  - Es el único dato de identidad que la agenda y los documentos necesitan, y está aislado por RLS.
  - Se conserva al desactivar la ficha: las citas y los documentos pasados lo siguen mostrando.
- **Usuario opcional**:
  - `user_id` es nulo para quien no tiene cuenta.
  - Si se enlaza, la clave ajena `(tenant_id, user_id) → clinic_membership` exige que sea miembro de la misma clínica.
  - La membresía tiene que ser de rol `ODONTOLOGO` o `HIGIENISTA` y casar con el área clínica: higiene dental es solo para higienistas.
  - Hay una ficha por usuario y clínica.
  - El enlace pasa de nulo a un usuario una sola vez y ya no cambia. Lo garantiza el trigger `practitioner_role_user_guard`: una cita pasada nunca muestra a otra persona.
- **La ficha sigue a la membresía**:
  - Desactivar la membresía, o darle un rol que no casa con el área, desactiva la ficha en la misma transacción.
  - Reactivar la membresía no reactiva la ficha: lo decide `ADMIN_CLINICA`.
  - Mientras se comprueba la membresía, se bloquea (`FOR SHARE`), para que no se desactive entre la comprobación y el commit.
- **Validación en el DTO y en `CHECK` de la base de datos**:
  - Área clínica: enumerado cerrado `PractitionerSpecialty`. En los textos visibles se llama «área clínica», nunca «especialidad».
  - Color: `#RRGGBB` en mayúsculas.
  - Número de colegiado:
    - Entre 3 y 12 letras o cifras, con un separador opcional, junto al código INE de la provincia de su colegio.
    - Obligatorio salvo en higiene dental.
    - Único entre las fichas activas de la clínica con la misma provincia.
- **Nunca se borra**: se desactiva. Gestionar la ficha es solo de `ADMIN_CLINICA`. La leen todos los roles de la clínica.
- **API pública para otros módulos**: `ActivePractitioners` en la raíz de `identity`.
  - `list` y `requireActive`, para las citas nuevas, que solo se dan con fichas activas.
  - `describe`, para pintar lo ya dado, inactivas incluidas.
  - M06 referenciará `practitioner_role_id`.

## Consecuencias

- El nombre de la ficha puede no coincidir con el de la cuenta de Keycloak. Es deliberado: son datos distintos, y cada uno tiene su dueño.
- Las fichas viven en `identity`, junto a las membresías, para que la desactivación en cascada sea de la misma transacción sin publicar eventos.
- El color de la propia ficha, que `configuracion.md` dejaba editar al odontólogo, pasa a las preferencias personales (M02-T10).
- Deshacer V012 a mano: `DROP TABLE practitioner_role; DROP FUNCTION guard_practitioner_role_user();`.
