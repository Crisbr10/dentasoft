# 0006 — Dominio intercambiable

Fecha: 2026-11-23 · Estado: aceptada

## Contexto

El dominio actual (`crisbr.es`, con subdominios `dentasoft-*`) es temporal. Cuando Dentasoft tenga dominio propio, el cambio no debe exigir tocar código ni recompilar con cambios manuales.

## Decisión

- **Ningún dominio ni URL pública escrita en el código** (backend, frontend, app móvil) ni en ficheros de configuración versionados, salvo `localhost` en el perfil `local`.
- **Backend**: las URL públicas salen de `dentasoft.urls.app`, `dentasoft.urls.api` y `dentasoft.urls.auth` (variables `DENTASOFT_URLS_*`), validadas al arrancar; CORS de `dentasoft.cors.allowed-origins`. Todo lo que construye enlaces (emails, WhatsApp, PDF, webhooks, OpenAPI) usa esas propiedades. `NoHardcodedUrlsTests` lo verifica.
- **Frontend**: las URL de la API y de Keycloak se leen de un fichero de configuración generado en el build a partir de variables de entorno de Vercel (`DENTASOFT_URLS_API`, `DENTASOFT_URLS_AUTH`), no de `environment.ts` con valores fijos.
- **Keycloak**: el realm versionado usa variables para redirect URIs, orígenes web y hostname; nada de dominios en el JSON.
- **Email**: el dominio de envío es una variable (`DENTASOFT_MAIL_FROM`), verificado en el proveedor.
- **App móvil**: la URL de la API se configura al emparejar la tablet (el código de emparejamiento la incluye), no en el binario.
- El procedimiento completo está en [`docs/operaciones/cambio-de-dominio.md`](../operaciones/cambio-de-dominio.md).

## Consecuencias

- Cambiar de dominio es configuración e infraestructura (DNS, túnel, variables, proveedores externos), sin cambios de código.
- Hay que mantener la guía de cambio de dominio al día cuando se añada un servicio que dependa del dominio.
