# M05 · Documentos

**Objetivo**: guardar radiografías, fotos y documentos del paciente de forma segura y generar PDF reutilizables por otros módulos.
**Depende de**: M04
**Paquete backend**: `documents` · **Frontend**: `features/documents`
**FHIR**: DocumentReference

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `document` | `tenant_id`, `patient_id`, `category` (radiografía, foto, consentimiento, presupuesto, informe, otro), `storage_key`, `original_name`, `mime_type`, `size_bytes`, `sha256`, `scan_status` (pendiente, limpio, infectado), `uploaded_by`, `deleted_at` |

Clave de almacenamiento: `{tenant_id}/patients/{patient_id}/{document_id}`. El bucket es privado y con versionado.

## Tareas

### M05-T01 · Abstracción de almacenamiento S3

- **Estado**: pendiente
- **Depende de**: M00-T03
- **Descripción**: interfaz `DocumentStorage` con implementación AWS SDK v2 (`endpointOverride`) que funciona con SeaweedFS (local, ADR 0007), Hetzner, OVHcloud o AWS; subida, descarga, URL firmada y borrado lógico.
- **Criterios de aceptación**:
  - [ ] Cambiar de proveedor es solo cambiar variables de entorno
  - [ ] Test de integración con un S3 compatible en Testcontainers (SeaweedFS, la misma imagen que en local)

### M05-T02 · Subida segura

- **Estado**: pendiente
- **Depende de**: M05-T01, M04-T01
- **Descripción**: subida con límite de tamaño, tipo real detectado con Apache Tika (lista blanca: imágenes, PDF, DICOM más adelante), huella SHA-256 y análisis con ClamAV; el documento no se puede descargar hasta estar `limpio`.
- **Criterios de aceptación**:
  - [ ] Un fichero renombrado (`.exe` como `.pdf`) se rechaza
  - [ ] Un fichero de prueba EICAR queda como `infectado` y no se sirve
  - [ ] Test de aislamiento entre clínicas

### M05-T03 · Descarga con URL firmada y auditoría

- **Estado**: pendiente
- **Depende de**: M05-T02
- **Descripción**: el backend comprueba permisos y devuelve una URL firmada de pocos minutos; cada descarga queda en `audit_log`.
- **Criterios de aceptación**:
  - [ ] Una URL caducada no funciona
  - [ ] Un usuario de otra clínica no obtiene URL

### M05-T04 · Servicio de generación de PDF

- **Estado**: pendiente
- **Depende de**: M00-T02
- **Descripción**: plantillas Thymeleaf con los datos y la identidad visual de la clínica, convertidas a PDF con OpenHTMLtoPDF; textos desde ficheros de mensajes. Lo usan presupuestos (M11) y consentimientos (M12).
- **Criterios de aceptación**:
  - [ ] PDF/A o, como mínimo, fuentes incrustadas y metadatos correctos
  - [ ] Plantilla de ejemplo con test de que se genera sin errores

### M05-T05 · Pantallas de documentos

- **Estado**: pendiente
- **Depende de**: M05-T03, M04-T06
- **Descripción**: pestaña de documentos en la ficha del paciente con subida por arrastrar y soltar, filtros por categoría y visor de imágenes y PDF.
- **Criterios de aceptación**:
  - [ ] Progreso de subida y estado del análisis visibles
  - [ ] El visor no descarga el fichero completo de golpe si es grande
