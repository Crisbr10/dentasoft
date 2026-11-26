# M03 · Entorno de pruebas y despliegue continuo

**Objetivo**: tener la app desplegada en el servidor de casa y en Vercel con cada fusión en `main`, accesible por HTTPS, para probar de extremo a extremo y recibir webhooks.
**Depende de**: M02
**Infra**: servidor Ubuntu 24.04 con Docker 29 (ya instalados), Cloudflare, Vercel
**Dominio**: temporal (`crisbr.es`). Todo hostname se configura por variables; para cambiarlo, sigue [`docs/operaciones/cambio-de-dominio.md`](../operaciones/cambio-de-dominio.md)
**Solo datos inventados**: este entorno nunca tiene datos de pacientes reales.

## Hostnames actuales (dominio temporal)

| Servicio | URL |
| --- | --- |
| Frontend | `https://dentasoft-staging.crisbr.es` |
| API | `https://dentasoft-api-staging.crisbr.es` |
| Keycloak | `https://dentasoft-auth-staging.crisbr.es` |

## Tareas

### M03-T01 · Endurecer el servidor de casa

- **Estado**: pendiente
- **Depende de**: —
- **Descripción**: usuario sin root en el grupo `docker`, SSH solo con clave, `ufw` (SSH solo desde la red local), `fail2ban` y actualizaciones automáticas de seguridad.
- **Criterios de aceptación**:
  - [ ] No se trabaja como `root` y `PasswordAuthentication no`
  - [ ] Solo el puerto 22 abierto y solo para la red local

### M03-T02 · Compose de pruebas

- **Estado**: pendiente
- **Depende de**: M00-T04, M00-T07
- **Descripción**: `infra/compose.staging.yml` con la imagen de GHCR, Keycloak en modo `start`, reinicio automático y `.env.staging` fuera de Git con secretos aleatorios.
- **Nota (M02-T01)**: Keycloak arranca con `start --import-realm` y las variables `DENTASOFT_*` del realm (ver `infra/README.md`, «Realm de Keycloak»), y sin montar `keycloak/local/` (usuarios de prueba). `--import-realm` solo crea el realm si no existe: los cambios posteriores del JSON no llegan a pruebas por sí solos. Hay que valorar si conviene usar [keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli) para aplicar los cambios del realm sin borrarlo (revisando sus opciones `import.managed.*` y `import.remote-state.*`, que deciden si borra lo que no está en el fichero, para que nunca toque los usuarios ni las organizaciones que crea la aplicación). Hasta decidirlo, los cambios se aplican a mano en la consola y en el JSON.
- **Criterios de aceptación**:
  - [ ] El stack arranca desde cero con un solo comando
  - [ ] Secretos generados con `openssl rand` y guardados en el gestor de contraseñas
  - [ ] En pruebas, el almacenamiento S3 es un bucket de un proveedor externo (no SeaweedFS). Si se reutilizara SeaweedFS, ningún puerto interno sin autenticación debe ser accesible fuera de su red (ver hallazgo de la revisión de M00-T03, [PR #96](https://github.com/Crisbr10/dentasoft/pull/96), y `infra/README.md`)

### M03-T03 · Cloudflare Tunnel y Access

- **Estado**: pendiente
- **Depende de**: M03-T02
- **Descripción**: túnel `cloudflared` como contenedor, hostnames públicos de la tabla y Cloudflare Access para `/admin` de Keycloak.
- **Criterios de aceptación**:
  - [ ] API y Keycloak responden por HTTPS sin abrir puertos en el router
  - [ ] La consola de administración de Keycloak pide autenticación de Cloudflare Access

### M03-T04 · Despliegue automático

- **Estado**: pendiente
- **Depende de**: M03-T02, M00-T07
- **Descripción**: runner autoalojado de GitHub Actions (etiqueta `staging`) como servicio y trabajo `deploy-staging` que descarga la imagen del commit y reinicia la app.
- **Criterios de aceptación**:
  - [ ] Fusionar en `main` despliega en pruebas sin intervención
  - [ ] El runner solo atiende a este repositorio privado

### M03-T05 · Frontend en Vercel

- **Estado**: pendiente
- **Depende de**: M00-T05
- **Descripción**: proyecto Vercel con *Root Directory* `frontend`, *Ignored Build Step* para cambios fuera de `frontend/`, configuración `staging` y dominio `dentasoft-staging.crisbr.es`; redirect URIs y orígenes en Keycloak y CORS del backend.
- **Criterios de aceptación**:
  - [ ] Login completo desde Vercel contra Keycloak de pruebas
  - [ ] Los previews de PR funcionan con el patrón de redirect URI acotado

### M03-T06 · Copias y monitorización

- **Estado**: pendiente
- **Depende de**: M03-T02
- **Descripción**: `pg_dump` nocturno comprimido a otro destino, prueba de restauración documentada y Uptime Kuma con avisos.
- **Criterios de aceptación**:
  - [ ] Restauración probada al menos una vez y anotada
  - [ ] Aviso si la API o Keycloak caen más de 5 minutos
