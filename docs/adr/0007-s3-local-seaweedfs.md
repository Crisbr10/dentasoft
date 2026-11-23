# 0007 — Almacenamiento S3 local con SeaweedFS en lugar de MinIO

Fecha: 2026-11-23 · Estado: aceptada

Modifica el punto de [ADR 0001](0001-arquitectura-inicial.md) que nombra MinIO como almacenamiento S3 en local. El resto de ADR 0001 sigue vigente.

## Contexto

ADR 0001 preveía MinIO como almacenamiento S3 compatible en local. MinIO dejó de publicar imágenes de su edición comunitaria: a 2026-11-23, `minio/minio` y `minio/mc` ya no existen en Docker Hub y no hay ninguna imagen pública mantenida. La tarea M00-T03 exige un bucket `dentasoft-docs` con versionado.

## Decisión

- En local, el almacenamiento S3 es **SeaweedFS** (`chrislusf/seaweedfs`, Apache 2.0), con la versión fijada en `infra/.env.example`.
- Un contenedor con AWS CLI crea el bucket, activa el versionado y se queda en reposo, sano, cuando el bucket está listo. No termina a propósito: `docker compose up --wait` falla con un contenedor ya terminado del que no depende ningún servicio (cambio hecho en la revisión de M00-T03).
- El backend sigue hablando S3 con AWS SDK v2 y un endpoint configurable: ningún código depende de SeaweedFS.

## Consecuencias

- Se mantiene una imagen con publicación activa y parches de seguridad.
- Probado en local: credenciales desde el entorno, credenciales incorrectas rechazadas y varias versiones del mismo objeto conservadas.
- Los tests de integración que mencionan MinIO en Testcontainers (M05-T01) tendrán que usar otra imagen compatible con S3.

## Alternativas descartadas

- Fijar la última etiqueta publicada de MinIO: software sin parches de seguridad.
- Garage: no soporta el versionado de buckets S3.
