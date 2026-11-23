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
pnpm build                 # exige las variables de «Configuración»
```

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `src/app/core/` | Configuración, i18n, iconos y layout base (cabecera, barra lateral y área de contenido) |
| `src/app/shared/` | Componentes, directivas y pipes reutilizables |
| `src/app/api/` | Cliente generado con `pnpm api:gen` (M00-T06) |
| `src/app/features/` | Una carpeta por módulo funcional, con carga diferida |
| `src/assets/i18n/` | Textos (`es.json`) |
| `src/assets/icons/` | Iconos SVG servidos desde el propio build |
| `src/styles/` | Tema de Material 3 y tokens de diseño (`--ds-*`) |
| `scripts/` | Generación de `config.json` y de la CSP, y sus tests |

## Configuración

Ninguna URL ni dominio se escribe en `src/` ([ADR 0006](../docs/adr/0006-dominio-intercambiable.md)); un test (`scripts/no-hardcoded-urls.test.mjs`) lo comprueba. Las URL salen de variables de entorno:

| Variable | Uso | En desarrollo |
| --- | --- | --- |
| `DENTASOFT_URLS_API` | URL base de la API | `http://localhost:8080` |
| `DENTASOFT_URLS_AUTH` | URL de Keycloak | `http://localhost:8180` |

- `scripts/runtime-config.mjs` escribe `public/config.json` con esas URL (el fichero no se versiona) y la app lo carga al arrancar. Cambiar de dominio es cambiar las variables y volver a desplegar, sin tocar código.
- `pnpm start` usa `localhost` si faltan las variables. **`pnpm build` falla si falta alguna**, si no es una URL absoluta o si no usa `https`.
- **Vercel**: define las dos variables en *Settings → Environment Variables* para **Production y también para Preview**; si no, los despliegues de vista previa fallan en el build.
- **CI** (M00-T07): el build usará dominios reservados `.test`, por ejemplo `https://api.dentasoft.test`.

## Seguridad en Vercel

`vercel.json` define la reescritura de SPA a `index.html` y las cabeceras de seguridad:

- **CSP en dos capas**. La cabecera de `vercel.json` no nombra ningún dominio (Vercel no lee variables en ese fichero) y lleva `frame-ancestors 'none'`, que solo funciona en cabecera. Tras el build, `scripts/csp.mjs` añade a `index.html` una CSP en `<meta>`, lo primero de `<head>`, que limita `connect-src` a la API y a Keycloak de ese build. El navegador aplica las dos y manda la más estricta.
- `style-src` admite `'unsafe-inline'` porque Angular inyecta los estilos de los componentes en tiempo de ejecución. Los scripts no: por eso `inlineCritical` está desactivado en `angular.json`.
- `X-Frame-Options: DENY`, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy` y `Cross-Origin-Resource-Policy`.
- **`Cross-Origin-Opener-Policy: same-origin`** funciona porque el login con Keycloak será **por redirección** (M02-T07). Un login en ventana emergente rompería con esta cabecera: no lo cambies a popup sin revisar la COOP.
- **Caché**: `config.json` va con `Cache-Control: no-cache`, para que un cambio de variables y un redespliegue se vean al momento. Los ficheros con hash (`main-*`, `chunk-*`, `polyfills-*`, `styles-*` y `media/`) van con caché larga e inmutable.

## Idioma y formato

- `lang="es"` en `index.html`, locale `es` registrado para fechas y números (`LOCALE_ID`) y euro como moneda por defecto.
- Transloco con `es` como idioma por defecto y de reserva. Ningún texto visible se escribe a mano: la regla `@angular-eslint/template/i18n` falla con texto o atributos literales en las plantillas.
