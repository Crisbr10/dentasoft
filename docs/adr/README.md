# Decisiones de arquitectura (ADR)

Cada decisión importante se documenta en un fichero numerado. Un ADR aceptado no se edita: si la decisión cambia, se crea otro que lo sustituye y se marca el antiguo como «sustituido por NNNN».

| Nº | Decisión | Estado |
| --- | --- | --- |
| [0001](0001-arquitectura-inicial.md) | Arquitectura inicial: monolito modular en contenedores | Aceptada |
| [0002](0002-multi-clinica-rls.md) | Multi-clínica con una base de datos, `tenant_id` y Row Level Security | Aceptada |
| [0003](0003-frontend-primeng-i18n.md) | Frontend Angular con PrimeNG e internacionalización con Transloco | Sustituida por 0008 |
| [0004](0004-firma-en-tablet-expo.md) | Firma de documentos con app Expo en tablet | Aceptada |
| [0005](0005-ia-fuera-del-mvp.md) | IA fuera del MVP y limitada a tareas administrativas | Aceptada |
| [0006](0006-dominio-intercambiable.md) | Dominio intercambiable: ninguna URL pública en el código | Aceptada |
| [0007](0007-s3-local-seaweedfs.md) | Almacenamiento S3 local con SeaweedFS en lugar de MinIO | Aceptada |
| [0008](0008-frontend-angular-material.md) | Frontend con Angular Material en lugar de PrimeNG (se mantiene Transloco) | Aceptada |
| [0009](0009-clinica-activa-scopedvalue-centinela.md) | Clínica activa en un `ScopedValue` y centinela `NO_CLINIC` en Hibernate | Aceptada |
| [0010](0010-clinica-en-postgresql-por-transaccion.md) | La clínica llega a PostgreSQL al empezar cada transacción | Aceptada |
| [0011](0011-rol-de-plataforma-en-base-de-datos.md) | El administrador de la plataforma usa su propio rol de base de datos | Aceptada |
| [0012](0012-registro-de-auditoria.md) | Registro de auditoría inmutable, sin datos personales y en la transacción del cambio | Aceptada |
| [0013](0013-procesos-sin-usuario-y-tareas-programadas.md) | Procesos sin usuario clínica a clínica y tareas programadas con ShedLock | Aceptada |
| [0014](0014-validacion-de-tokens-y-limite-antes-de-autenticar.md) | Validación de tokens de Keycloak y límite por IP antes de autenticar | Aceptada |
| [0015](0015-roles-de-clinica-en-base-de-datos.md) | Roles de clínica en la base de datos y usuarios aislados por usuario | Aceptada |
| [0016](0016-admin-api-de-keycloak-desde-el-backend.md) | Admin API de Keycloak desde el backend e invitaciones | Aceptada |
| [0017](0017-fichas-de-profesional-por-clinica.md) | Fichas de profesional por clínica (FHIR PractitionerRole) | Aceptada |

## Plantilla

```markdown
# NNNN — Título

Fecha: AAAA-MM-DD · Estado: propuesta | aceptada | sustituida por NNNN

## Contexto
Qué problema hay y qué restricciones importan.

## Decisión
Qué se decide, en frases concretas.

## Consecuencias
Qué gana y qué cuesta; qué queda más fácil y qué más difícil.

## Alternativas descartadas
Opción y motivo, una línea cada una.
```
