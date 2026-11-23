# M13 · Preparación para producción

**Objetivo**: cerrar el MVP listo para la primera clínica real: infraestructura en la UE, copias, observabilidad, seguridad verificada y cumplimiento legal.
**Depende de**: todos los módulos anteriores
**Guía**: Fase 12 del documento de arranque

## Tareas

### M13-T01 · Revisión de seguridad por módulo

- **Estado**: pendiente
- **Depende de**: M12-T05
- **Descripción**: recorrer OWASP ASVS (nivel 2) en cada módulo, CSP estricta en el frontend, Keycloak en modo producción y revisión de permisos de la base de datos.
- **Criterios de aceptación**:
  - [ ] Checklist ASVS por módulo guardada en `docs/seguridad/`
  - [ ] Ningún hallazgo crítico o alto abierto

### M13-T02 · Escaneo dinámico y prueba de penetración

- **Estado**: pendiente
- **Depende de**: M13-T01
- **Descripción**: OWASP ZAP semanal contra pruebas en la CI y prueba de penetración externa antes de la primera clínica.
- **Criterios de aceptación**:
  - [ ] Informe del pentest con todos los hallazgos críticos y altos corregidos

### M13-T03 · Infraestructura de producción

- **Estado**: pendiente
- **Depende de**: M03-T04
- **Descripción**: VPS en la UE (Hetzner u OVHcloud) endurecido, Traefik con HTTPS, `compose.prod.yml`, almacenamiento S3 de producción con versionado y bloqueo de objeto, y dominios definitivos.
- **Criterios de aceptación**:
  - [ ] Solo los puertos 80 y 443 abiertos
  - [ ] Despliegue desde la CI con aprobación manual

### M13-T04 · Copias de seguridad y recuperación

- **Estado**: pendiente
- **Depende de**: M13-T03
- **Descripción**: pgBackRest (completa semanal, incremental diaria y WAL continuo) a otro proveedor, copia de documentos con `rclone`, copia de Keycloak y procedimiento de recuperación ante desastres.
- **Criterios de aceptación**:
  - [ ] Restauración a un minuto concreto probada y anotada
  - [ ] Objetivos de recuperación (RPO y RTO) documentados

### M13-T05 · Observabilidad y alertas

- **Estado**: pendiente
- **Depende de**: M13-T03
- **Descripción**: métricas con Prometheus y Grafana, logs con Loki, errores con Sentry (región UE) o GlitchTip, y alertas (API caída, disco, copia fallida, certificado, picos de 401/403).
- **Criterios de aceptación**:
  - [ ] Una alerta de prueba llega por el canal elegido
  - [ ] Ningún dato personal en métricas, logs ni errores enviados a terceros

### M13-T06 · Cumplimiento legal y fiscal

- **Estado**: pendiente
- **Depende de**: —
- **Descripción**: alta como autónomo; contrato de encargo del tratamiento con cada clínica y lista de subencargados; registro de actividades de tratamiento; política de privacidad y condiciones del servicio; valoración de la EIPD con un experto; procedimiento de brechas (72 horas); verificación del negocio en Meta, Tech Provider y Embedded Signup.
- **Criterios de aceptación**:
  - [ ] Documentos legales revisados por un profesional
  - [ ] Una clínica conecta su número de WhatsApp con Embedded Signup

### M13-T07 · Puesta en marcha de la primera clínica

- **Estado**: pendiente
- **Depende de**: M13-T02, M13-T04, M13-T05, M13-T06
- **Descripción**: guía de usuario básica, alta de la clínica, configuración (gabinetes, profesionales, catálogo, plantillas), formación y periodo de acompañamiento.
- **Criterios de aceptación**:
  - [ ] La clínica trabaja una semana completa con Dentasoft
  - [ ] Incidencias recogidas como issues en GitHub
