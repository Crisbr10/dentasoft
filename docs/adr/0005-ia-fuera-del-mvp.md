# 0005 — IA fuera del MVP y limitada a tareas administrativas

Fecha: 2026-11-23 · Estado: aceptada

## Contexto

Se quiere incorporar IA (recordatorios, consultas, asistentes), pero no en la primera versión. Las funciones que ayudan a diagnosticar o decidir tratamientos convertirían el software en producto sanitario regulado y en IA de alto riesgo.

## Decisión

- La primera versión no incluye IA.
- Cuando llegue, la IA se limita a tareas **administrativas y de apoyo**: recordatorios inteligentes, respuestas a preguntas frecuentes, dictado de notas revisadas por el profesional, asistente de agenda y búsqueda en documentos.
- Nunca diagnóstico, lectura de radiografías ni propuestas de tratamiento.
- Desde el MVP se prepara: eventos de negocio, API completa con OpenAPI (exponible como herramientas MCP), datos estructurados, consentimiento por canal y finalidad, módulo `ai` reservado, funciones activables por clínica y PostgreSQL con `pgvector`.
- Reglas de integración: proveedor con datos en la UE y sin retención, datos mínimos, defensas contra inyección de instrucciones, confirmación humana de acciones y registro de cada interacción. Un asistente que habla con pacientes debe decir que es una IA.

## Consecuencias

- El MVP no carga con obligaciones regulatorias de IA.
- Añadir IA será conectar piezas sobre una base ya preparada.
