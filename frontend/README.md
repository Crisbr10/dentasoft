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
```

## Estructura

| Carpeta             | Contenido                                                                               |
| ------------------- | --------------------------------------------------------------------------------------- |
| `src/app/core/`     | Configuración, i18n, iconos y layout base (cabecera, barra lateral y área de contenido) |
| `src/app/shared/`   | Componentes, directivas y pipes reutilizables                                           |
| `src/app/api/`      | Cliente generado con `pnpm api:gen` (M00-T06)                                           |
| `src/app/features/` | Una carpeta por módulo funcional, con carga diferida                                    |
| `src/assets/i18n/`  | Textos (`es.json`)                                                                      |
| `src/assets/icons/` | Iconos SVG servidos desde el propio build                                               |
| `src/styles.scss`   | Tema de Material 3 (`mat.theme`)                                                        |
| `src/styles/`       | Paletas generadas y tokens de diseño (`--ds-*`)                                         |
| `scripts/`          | Generación de `config.json` y de la CSP, y sus tests                                    |

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

## Seguridad en Vercel

`vercel.json` define la reescritura de SPA a `index.html` y las cabeceras de seguridad. La reescritura excluye las rutas que acaban en una extensión de fichero del build (`.js`, `.css`, `.json`, `.svg`, `.woff2`…; la lista está en `vercel.json`), así que ninguna ruta de la app debe acabar así: un fichero que no existe, como un chunk de una versión anterior, da 404 en lugar de devolver `index.html` con la caché larga de los ficheros con hash.

- **CSP en dos capas**. La cabecera de `vercel.json` no nombra ningún dominio (Vercel no lee variables en ese fichero) y lleva `frame-ancestors 'none'`, que solo funciona en cabecera. Tras el build, `scripts/csp.mjs` añade a `index.html` una CSP en `<meta>`, lo primero de `<head>`, que limita `connect-src` a la API y a Keycloak de ese build. El navegador aplica las dos: una petición debe cumplir ambas.
- `config.json` y la CSP del `<meta>` se generan juntos en el build. Si algún día se cambia `config.json` después del build (por ejemplo, en una imagen de contenedor), hay que regenerar también la CSP, o el navegador bloqueará los dominios nuevos.
- `style-src` admite `'unsafe-inline'` porque Angular inyecta los estilos de los componentes en tiempo de ejecución. Los scripts no: `inlineCritical` está desactivado en `angular.json` porque genera `<link … onload="…">`, un manejador en línea que `script-src 'self'` bloquea.
- `X-Frame-Options: DENY`, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy` y `Cross-Origin-Resource-Policy`.
- **`Cross-Origin-Opener-Policy: same-origin`** funciona porque el login con Keycloak será **por redirección** (M02-T07). Un login en ventana emergente rompería con esta cabecera: no lo cambies a popup sin revisar la COOP.
- **Caché**: `config.json` va con `Cache-Control: no-cache`, para que un cambio de variables y un redespliegue se vean al momento. Los ficheros con hash (`main-*`, `chunk-*`, `polyfills-*`, `styles-*` y `media/`) van con caché larga e inmutable.

## Licencias de terceros

Los iconos de `src/assets/icons/` son de Material Icons (Google, Apache License 2.0). Ver [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Idioma y formato

- `lang="es"` en `index.html`, locale `es` registrado para fechas y números (`LOCALE_ID`) y euro como moneda por defecto.
- Transloco con `es` como idioma por defecto y de reserva. Ningún texto visible se escribe a mano: la regla `@angular-eslint/template/i18n` falla con texto o atributos literales en las plantillas. Es la regla de la i18n nativa de Angular: arréglalo con una clave de Transloco, **nunca** con un atributo `i18n` ni con `pnpm lint --fix`. No detecta literales dentro de `{{ }}`.
