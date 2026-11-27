# Frontend de Dentasoft

Angular 22 sin SSR, con Angular Material y CDK ([ADR 0008](../docs/adr/0008-frontend-angular-material.md)), Transloco para los textos y pnpm. Se despliega en Vercel.

## Requisitos

- Node 24 (versión en `.nvmrc`) y pnpm (versión en `packageManager` de `package.json`).

## Comandos

```powershell
pnpm install
pnpm start                 # http://localhost:4200, con la API en localhost:8080
pnpm lint                  # ESLint, incluida la regla de textos sin Transloco
pnpm test --watch=false    # tests de los scripts (node --test) y de la app (Vitest)
pnpm build                 # exige las variables de «Configuración»; no admite argumentos
pnpm format                # aplica Prettier (pnpm format:check solo comprueba)
pnpm api:gen               # regenera src/app/api desde el OpenAPI del backend local
```

## Cliente de la API

`src/app/api/` es código generado con [ng-openapi-gen](https://github.com/cyclosproject/ng-openapi-gen) a partir del OpenAPI del backend. **Va en el repositorio y no se edita a mano**. ESLint, Prettier y las revisiones de código no lo incluyen.

- **Cuándo regenerarlo**: en el mismo PR que cambia un endpoint o un DTO del backend. Así el cliente nunca se desfasa de la API.
- **Cómo**: arranca el backend con el perfil local (`cd backend; .\mvnw.cmd spring-boot:run`, o el servicio `app` de Compose, que recibe las variables de `infra/.env`: `docker compose -f compose.yml -f compose.local.yml --profile app up -d --build --wait app` desde `infra/`) y ejecuta `pnpm api:gen`. Después, para el servicio con `docker compose … stop app`. Por defecto lee `http://localhost:8080/v3/api-docs`; para leer otra URL, usa la variable `DENTASOFT_API_DOCS_URL`.
- **Respuestas en JSON**: el backend documenta sus respuestas como `application/json` (`springdoc.default-produces-media-type`). Con `*/*`, ng-openapi-gen las pediría como `blob` y el cliente no leería el cuerpo.
- **Sin URL en el código**: `scripts/api-gen.mjs` quita `servers` del documento antes de generar, para que no quede ninguna URL escrita. La app fija la URL base del cliente desde `config.json` con `provideApiClient()` (`src/app/core/config/api-client.ts`). `no-hardcoded-urls` también revisa el código generado. Solo ignora en él las líneas que son enteramente comentario, como los enlaces a documentación. Para decidirlo usa el parser de TypeScript, así que una línea que mezcla un comentario y código se revisa igual.
- La configuración del generador está en `ng-openapi-gen.json`. Los ficheros que dejan de generarse se borran solos.

## Estructura

| Carpeta             | Contenido                                                                              |
| ------------------- | -------------------------------------------------------------------------------------- |
| `src/app/core/`     | Configuración, login y sesión, clínica activa, interceptor, i18n, iconos y layout base |
| `src/app/shared/`   | Componentes, directivas y pipes reutilizables                                          |
| `src/app/api/`      | Cliente generado con `pnpm api:gen` (ver «Cliente de la API»)                          |
| `src/app/features/` | Una carpeta por módulo funcional, con carga diferida                                   |
| `src/assets/i18n/`  | Textos (`es.json`)                                                                     |
| `src/assets/icons/` | Iconos SVG servidos desde el propio build                                              |
| `src/styles.scss`   | Tema de Material 3 (`mat.theme`)                                                       |
| `src/styles/`       | Paletas generadas y tokens de diseño (`--ds-*`)                                        |
| `scripts/`          | Generación de `config.json`, de la CSP y del cliente de la API, y sus tests            |

## Configuración

Ninguna URL ni dominio se escribe en `src/` ([ADR 0006](../docs/adr/0006-dominio-intercambiable.md)); un test (`scripts/no-hardcoded-urls.test.mjs`) detecta URL absolutas (`http`, `https`, `ws`, `wss`), URL sin protocolo (`//host`) y el dominio actual. En los tests solo valen dominios `.test`. Las URL salen de variables de entorno:

| Variable              | Uso                | En desarrollo           |
| --------------------- | ------------------ | ----------------------- |
| `DENTASOFT_URLS_API`  | URL base de la API | `http://localhost:8080` |
| `DENTASOFT_URLS_AUTH` | URL de Keycloak    | `http://localhost:8180` |

- `scripts/runtime-config.mjs` escribe `public/config.json` con esas URL (el fichero no se versiona) y la app lo carga al arrancar. Cambiar de dominio es cambiar las variables y volver a desplegar, sin tocar código.
- `pnpm start` usa `localhost` si faltan las variables. **`pnpm build` falla si falta alguna**, si no es una URL absoluta o si no usa `https`.
- **Vercel**: define las dos variables en _Settings → Environment Variables_ para **Production y también para Preview**; si no, los despliegues de vista previa fallan en el build.
- **CI** (M00-T07): el build usará dominios reservados `.test`, por ejemplo `https://api.dentasoft.test`.

## Login y sesión

El login es con Keycloak (realm `dentasoft`, cliente público `dentasoft-web`), por redirección, con código y PKCE ([angular-auth-oidc-client](https://github.com/damienbod/angular-auth-oidc-client), `src/app/core/auth/`).

- **Tokens en `sessionStorage`**, nunca en `localStorage`: terminan con la pestaña.
- **Renovación con el refresh token**, sin iframes de Keycloak (`silentRenew` sin `silentRenewUrl` y sin `checkSession`), así que la CSP mantiene `frame-src 'none'`. Sin `offline_access`: la sesión termina con la de Keycloak.
- **Cerrar sesión** cierra también la sesión de Keycloak (`end_session_endpoint`). Antes de salir, borra todo `sessionStorage` y el estado en memoria (clínica activa y `/me`).
- **Interceptor** (`src/app/core/http/api-interceptor.ts`): añade `Authorization` solo a las peticiones cuyo origen, ya analizada la URL, es exactamente el de `apiUrl`, nunca por prefijo del texto. `X-Clinic-Id` va solo a `/api/v1/clinic/**`.
  - Un 401 renueva el token una vez y repite la petición. Si Keycloak rechaza el refresh token, o la petición repetida vuelve a dar 401, la sesión ha terminado: vuelve al login y, tras él, a la misma página. Si Keycloak o la red no responden, la sesión sigue: la librería conserva los tokens en ese caso y lo vuelve a intentar.
  - Un 403 de `/api/v1/clinic/**` vuelve a leer `/me`, una sola vez aunque fallen varias peticiones a la vez. El backend da la misma respuesta cuando rechaza la clínica y cuando falta el rol, así que solo si la clínica ya no se puede usar (membresía terminada o clínica suspendida) se va al selector.
- **Clínica activa** (`src/app/core/clinic/active-clinic.ts`): sale de `GET /api/v1/me`. Con una sola clínica se elige sola; con varias, el selector (`/clinica`) las muestra, incluidas las suspendidas (marcadas y sin poder elegirse), y las invitaciones pendientes, que se aceptan allí. Un `PLATFORM_ADMIN` sin clínicas ni invitaciones va a `/configuracion/plataforma`. Cambiar de clínica vuelve a crear la página, así que cada pantalla lee los datos de la clínica nueva.
- **Guardias y menú**: las guardias (`guards.ts`) y el menú por rol (`core/layout/navigation.ts`) son solo comodidad. La seguridad está en el backend, que comprueba el token, la membresía y el rol en cada petición.

## Apartado de Configuración

- **Rutas** (`src/app/features/configuration/`): `/configuracion` con una sección por ruta (`clinica`, `usuarios`, `profesionales`, `plataforma`). La antigua `/plataforma` redirige a `/configuracion/plataforma`, y `/clinica` sigue siendo el selector de clínica.
- **Secciones visibles**: las da el backend según la matriz de [`docs/plan/configuracion.md`](../docs/plan/configuracion.md): `GET /api/v1/clinic/configuration/sections` con el rol de la clínica activa y, para `PLATFORM_ADMIN`, `GET /api/v1/platform/configuration/sections`. Abrir por URL una sección que no se ve lleva a `/configuracion/sin-acceso`; la API de esa sección responde 403 igualmente. Una sección nueva necesita su fila en `ConfigurationSection` (backend) y su entrada en `SECTIONS` y en las rutas (`configuration.routes.ts`).
- **Formularios** (`src/app/shared/forms/`): `toSaveFailure` pone en cada control los errores de validación del servidor (`errors[{field, message}]` del ProblemDetail) y devuelve el motivo del fallo; `<app-form-actions>` muestra ese motivo (también el de un conflicto de versión, 409) y los botones de guardar y descartar. Una sección que el usuario solo ve no los muestra.
- **Secretos** (`<app-secret-field>`): solo muestra «configurado» o «sin configurar», y cuándo y quién lo cambió por última vez. Nunca muestra ningún carácter del secreto, ni recibe el valor guardado: su valor en el formulario es el secreto nuevo que se escribe, o `null` para conservar el guardado.

## Seguridad en Vercel

`vercel.json` define la reescritura de SPA a `index.html` y las cabeceras de seguridad. La reescritura excluye las rutas que acaban en una extensión de fichero del build (`.js`, `.css`, `.json`, `.svg`, `.woff2`…; la lista está en `vercel.json`), así que ninguna ruta de la app debe acabar así: un fichero que no existe, como un chunk de una versión anterior, da 404 en lugar de devolver `index.html` con la caché larga de los ficheros con hash.

- **CSP en dos capas**. La cabecera de `vercel.json` no nombra ningún dominio (Vercel no lee variables en ese fichero) y lleva `frame-ancestors 'none'`, que solo funciona en cabecera. Las dos capas llevan `frame-src 'none'`: la sesión se renueva con el refresh token, sin iframes de Keycloak. Tras el build, `scripts/csp.mjs` añade a `index.html` una CSP en `<meta>`, lo primero de `<head>`, que limita `connect-src` a la API y a Keycloak de ese build. El navegador aplica las dos: una petición debe cumplir ambas.
- `config.json` y la CSP del `<meta>` se generan juntos en el build. Si algún día se cambia `config.json` después del build (por ejemplo, en una imagen de contenedor), hay que regenerar también la CSP, o el navegador bloqueará los dominios nuevos.
- `style-src` admite `'unsafe-inline'` porque Angular inyecta los estilos de los componentes en tiempo de ejecución. Los scripts no: `inlineCritical` está desactivado en `angular.json` porque genera `<link … onload="…">`, un manejador en línea que `script-src 'self'` bloquea.
- `X-Frame-Options: DENY`, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy` y `Cross-Origin-Resource-Policy`.
- **`Cross-Origin-Opener-Policy: same-origin`** funciona porque el login con Keycloak es **por redirección** (M02-T07). Un login en ventana emergente rompería con esta cabecera: no lo cambies a popup sin revisar la COOP.
- **Caché**: `config.json` va con `Cache-Control: no-cache`, para que un cambio de variables y un redespliegue se vean al momento. Los ficheros con hash (`main-*`, `chunk-*`, `polyfills-*`, `styles-*` y `media/`) van con caché larga e inmutable.

## Licencias de terceros

Los iconos de `src/assets/icons/` son de Material Icons (Google, Apache License 2.0). Ver [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Idioma y formato

- `lang="es"` en `index.html`, locale `es` registrado para fechas y números (`LOCALE_ID`) y euro como moneda por defecto.
- Transloco con `es` como idioma por defecto y de reserva. Ningún texto visible se escribe a mano: la regla `@angular-eslint/template/i18n` falla con texto o atributos literales en las plantillas. Es la regla de la i18n nativa de Angular: arréglalo con una clave de Transloco, **nunca** con un atributo `i18n` ni con `pnpm lint --fix`. No detecta literales dentro de `{{ }}`.
