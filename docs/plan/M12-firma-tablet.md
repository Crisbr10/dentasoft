# M12 · Firma en tablet (Expo)

**Objetivo**: que el paciente firme consentimientos y presupuestos en una tablet de la clínica y que quede un PDF firmado, inalterable y con evidencias.
**Depende de**: M05, M11
**Paquete backend**: `signatures` · **App**: `mobile/` (Expo) · **Frontend**: `features/signatures`
**ADR**: [0004](../adr/0004-firma-en-tablet-expo.md)
**FHIR**: Consent, Provenance

## Modelo de datos

| Tabla | Campos principales |
| --- | --- |
| `signing_device` | `tenant_id`, `name`, `location` (recepción, gabinete), `paired_at`, `last_seen_at`, `revoked_at` |
| `consent_template` | `tenant_id`, `code`, `title`, contenido con variables, `treatment_ids` relacionados, `version` |
| `signature_request` | `tenant_id`, `patient_id`, `document_type` (consentimiento, presupuesto), referencia, `device_id`, `status` (pendiente, mostrado, firmado, rechazado, caducado), `expires_at` |
| `signature_evidence` | `tenant_id`, `request_id`, `signed_at`, `device_id`, `sent_by`, `document_sha256`, `signed_pdf_document_id`, trazo de la firma |

## Tareas

### M12-T01 · Emparejamiento de tablets

- **Estado**: pendiente
- **Depende de**: M02-T04
- **Descripción**: `ADMIN_CLINICA` genera un código de un solo uso; la tablet lo canjea por un token de dispositivo (flujo de dispositivo OAuth2 en Keycloak o token propio de vida limitada con rotación), revocable desde la web.
- **Criterios de aceptación**:
  - [ ] El código caduca a los 10 minutos y sirve una sola vez
  - [ ] Una tablet revocada deja de recibir solicitudes al instante
  - [ ] El token del dispositivo solo permite operar con solicitudes de firma

### M12-T02 · Solicitudes de firma y tiempo real

- **Estado**: pendiente
- **Depende de**: M12-T01, M06-T06
- **Descripción**: crear solicitudes desde la web y entregarlas a la tablet elegida por Server-Sent Events (con Redis Pub/Sub para varias réplicas); estados y caducidad.
- **Criterios de aceptación**:
  - [ ] La tablet recibe la solicitud en menos de 2 segundos
  - [ ] Una solicitud caducada no se puede firmar

### M12-T03 · Plantillas de consentimiento

- **Estado**: pendiente
- **Depende de**: M05-T04, M08-T01
- **Descripción**: plantillas de consentimiento informado por clínica y tratamiento, con variables (paciente, tratamiento, profesional, fecha) y versionado; generación del PDF con M05.
- **Criterios de aceptación**:
  - [ ] Cambiar una plantilla no afecta a documentos ya firmados
  - [ ] Plantillas de ejemplo marcadas para revisión legal de cada clínica

### M12-T04 · App Expo

- **Estado**: pendiente
- **Depende de**: M12-T02
- **Descripción**: proyecto Expo con TypeScript en `mobile/` (EAS para compilar): pantallas de emparejar, espera, lectura del PDF completo, firma (react-native-signature-canvas) y confirmación; textos con i18n.
- **Criterios de aceptación**:
  - [ ] El botón de firmar solo se activa tras recorrer el documento hasta el final
  - [ ] Tras firmar, el PDF se borra del dispositivo y la app vuelve a la espera
  - [ ] Funciona en tablet Android y en iPad

### M12-T05 · Sellado del PDF y evidencias

- **Estado**: pendiente
- **Depende de**: M12-T04, M05-T02
- **Descripción**: el backend incrusta la firma y una hoja de evidencias en el PDF, calcula el SHA-256, lo guarda con bloqueo de objeto (WORM) en S3 y registra `signature_evidence`; si era un presupuesto, dispara la aceptación (M11-T04); si era un consentimiento, actualiza `consent_record` (M04-T04).
- **Criterios de aceptación**:
  - [ ] El PDF firmado no se puede sobrescribir ni borrar en el almacenamiento
  - [ ] La huella guardada coincide con el fichero descargado

### M12-T06 · Seguridad del dispositivo y distribución

- **Estado**: pendiente
- **Depende de**: M12-T04
- **Descripción**: modo quiosco documentado (fijación de pantalla en Android, Acceso guiado en iPad), sin caché de datos de pacientes, distribución interna con EAS y revisión con un asesor de si la app cae bajo el Reglamento de Ciberresiliencia.
- **Criterios de aceptación**:
  - [ ] Guía de instalación para la clínica en `mobile/README.md`
  - [ ] Conclusión de la revisión CRA anotada en un ADR

### M12-T07 · Pantallas web de firma

- **Estado**: pendiente
- **Depende de**: M12-T02
- **Descripción**: enviar a firmar desde la ficha del paciente o del presupuesto, ver el estado en vivo, gestionar tablets y plantillas.
- **Criterios de aceptación**:
  - [ ] Recepción ve qué tablets están conectadas
