# 0001 — Arquitectura inicial: monolito modular en contenedores

Fecha: 2026-11-23 · Estado: aceptada

## Contexto

Un solo desarrollador construye un SaaS para varias clínicas dentales que trata datos de salud. Necesita entregar pronto, desplegar de forma sencilla en un VPS y poder escalar más adelante sin reescribir.

## Decisión

- Backend como **monolito modular**: Spring Boot 4 sobre Java 25 con **Spring Modulith**, un paquete por módulo funcional y comunicación entre módulos por eventos.
- **Todo en contenedores** desde el inicio (Docker Compose), cumpliendo los requisitos de Kubernetes: app sin estado, configuración por variables de entorno, sondas de salud, apagado ordenado, tareas programadas con ShedLock y tiempo real repartido con Redis Pub/Sub.
- PostgreSQL, Keycloak, Redis y almacenamiento S3 compatible (MinIO en local) como servicios de apoyo.
- Frontend Angular como SPA estática en Vercel; el navegador llama directamente a la API, así que los datos de pacientes no pasan por Vercel.
- Entornos: desarrollo en Windows con Docker Desktop, pruebas en un servidor Ubuntu propio expuesto con Cloudflare Tunnel, producción en un VPS de la UE.
- Monorepo con `backend/`, `frontend/`, `mobile/`, `infra/` y `docs/`.

## Consecuencias

- Un único despliegue y una sola base de código: rápido de desarrollar y depurar.
- Cualquier módulo se puede extraer como servicio si algún día lo necesita, porque sus fronteras ya están verificadas por `ModularityTests`.
- El salto a Kubernetes es de infraestructura (manifiestos o Helm), no de código.

## Alternativas descartadas

- Microservicios desde el inicio: complejidad de red, datos y despliegue sin beneficio para un equipo de una persona.
- Despliegue sin contenedores: entornos distintos entre local, pruebas y producción.
