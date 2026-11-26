# Cambio de dominio

Procedimiento para pasar Dentasoft del dominio temporal (`crisbr.es`) a uno definitivo, o para cambiar cualquier hostname. No requiere cambios de código (ver [ADR 0006](../adr/0006-dominio-intercambiable.md)). Hazlo primero en **pruebas** y, cuando funcione, repítelo en producción.

## Hostnames actuales

| Servicio | Pruebas | Producción |
| --- | --- | --- |
| Frontend | `dentasoft-staging.crisbr.es` | por definir |
| API | `dentasoft-api-staging.crisbr.es` | por definir |
| Keycloak | `dentasoft-auth-staging.crisbr.es` | por definir |
| Envío de emails | `dentasoft-mail.crisbr.es` | por definir |

Con un dominio propio (por ejemplo `dentasoft.es`) se recomiendan subdominios de un nivel: `app.`, `api.`, `auth.` y `mail.`.

## Pasos

1. **DNS**: añade el dominio a Cloudflare (o al proveedor de DNS) y baja el TTL de los registros actuales unos días antes.
2. **Túnel o proxy** (pruebas: Cloudflare Tunnel; producción: Traefik): crea los nuevos hostnames apuntando a los mismos servicios. Mantén los antiguos activos durante la transición.
3. **Variables del backend** (`.env.staging` o `.env.prod`): `DENTASOFT_URLS_APP`, `DENTASOFT_URLS_API`, `DENTASOFT_URLS_AUTH`, `DENTASOFT_CORS_ALLOWED_ORIGINS` y `DENTASOFT_MAIL_FROM`. Reinicia la app.
4. **Keycloak**: variable de hostname (`KC_HOSTNAME`) y `DENTASOFT_URLS_APP`, de la que salen la redirect URI, el origen web y la redirección tras cerrar sesión del cliente `dentasoft-web`. La variable solo se aplica cuando se crea el realm (`--import-realm` no toca uno que ya existe), así que en un entorno en marcha cambia también esos tres valores del cliente en la consola de administración (o con `kcadm.sh`). Durante la transición, deja permitidos los dos dominios, siempre como valores exactos y sin comodines. Ver [Realm de Keycloak](../../infra/README.md#realm-de-keycloak).
5. **Vercel**: añade el dominio nuevo en *Settings → Domains*, actualiza `DENTASOFT_URLS_API` y `DENTASOFT_URLS_AUTH` y vuelve a desplegar.
6. **Email (Resend)**: añade y verifica el nuevo dominio de envío (SPF, DKIM, DMARC). Cambia `DENTASOFT_MAIL_FROM` solo cuando esté verificado.
7. **WhatsApp (Meta)**: en la app de Meta, cambia la *Callback URL* del webhook a `https://<api-nueva>/api/webhooks/whatsapp` y verifica. Si hay clínicas con app propia, tienen que cambiarla en la suya (avísales con antelación).
8. **Cloudflare Access**: actualiza la aplicación que protege `/admin` de Keycloak.
9. **Tablets de firma**: vuelve a emparejarlas (el código lleva la nueva URL de la API).
10. **Comprobación**: login completo, crear una cita, recibir un recordatorio por email y por WhatsApp, firmar un documento en tablet, y que la CSP del frontend permita los nuevos dominios.
11. **Retirada**: tras un periodo de transición, quita los hostnames antiguos y los orígenes antiguos de CORS y Keycloak. Configura redirecciones 301 del dominio antiguo al nuevo para el frontend.
12. **Actualiza la tabla de hostnames de este documento** y la del módulo M03.
