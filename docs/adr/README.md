# Decisiones de arquitectura (ADR)

Cada decisión importante se documenta en un fichero numerado. Un ADR aceptado no se edita: si la decisión cambia, se crea otro que lo sustituye y se marca el antiguo como «sustituido por NNNN».

| Nº | Decisión | Estado |
| --- | --- | --- |
| [0001](0001-arquitectura-inicial.md) | Arquitectura inicial: monolito modular en contenedores | Aceptada |
| [0002](0002-multi-clinica-rls.md) | Multi-clínica con una base de datos, `tenant_id` y Row Level Security | Aceptada |
| [0003](0003-frontend-primeng-i18n.md) | Frontend Angular con PrimeNG e internacionalización con Transloco | Aceptada |
| [0004](0004-firma-en-tablet-expo.md) | Firma de documentos con app Expo en tablet | Aceptada |
| [0005](0005-ia-fuera-del-mvp.md) | IA fuera del MVP y limitada a tareas administrativas | Aceptada |
| [0006](0006-dominio-intercambiable.md) | Dominio intercambiable: ninguna URL pública en el código | Aceptada |

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
