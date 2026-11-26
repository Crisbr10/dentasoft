# 0014 — Validación de tokens de Keycloak y límite por IP antes de autenticar

Fecha: 2026-11-26 · Estado: aceptada

## Contexto

El backend valida los tokens de acceso de Keycloak como OAuth2 Resource Server (M02-T02). Había cuatro restricciones:

- **Dominio**: el dominio cambia por entorno (ADR 0006), así que el emisor no puede ir escrito en el código. Además, dentro de Docker el backend llega a Keycloak por otra dirección que la del emisor.
- **Arranque**: la aplicación tiene que arrancar aunque Keycloak no responda, y un Keycloak lento no puede dejar las peticiones colgadas.
- **Auditoría**: `created_by`, `updated_by` y `audit_log.actor_id` son `uuid` y deben guardar el usuario real (ADR 0012).
- **Límite de peticiones**: el limitador corría después de Spring Security, así que las peticiones rechazadas con 401 nunca se contaban.

## Decisión

- **Validación**:
  - El emisor es `${dentasoft.urls.auth}/realms/${dentasoft.auth.realm}`, y la audiencia debe incluir `dentasoft-api`.
  - Solo se acepta RS256, con las claves del realm.
  - `exp` es obligatorio, con 30 s de tolerancia; `sub` debe ser un UUID y `typ` debe ser `Bearer`.
  - Los roles del realm que el código conoce (`PLATFORM_ADMIN`) se convierten en autoridades `ROLE_`; el resto se ignora. Todo esto vive en el módulo `identity`.
- **Claves**:
  - `dentasoft.auth.jwk-set-uri` es explícita y se pide con el primer token, nunca al arrancar.
  - Se pide con 2 s para conectar y 3 s para leer, se guarda 5 minutos y se vuelve a pedir como mucho dos veces cada 30 s. El `JWKSource` se construye con Nimbus, porque el constructor de Spring desactiva ese límite de recargas.
- **Cadenas**:
  - Las tres cadenas comparten la base sin estado de `shared.ApiSecurity`. CSRF se desactiva solo ahí: el token Bearer va en una cabecera y no hay cookies (ADR 0011).
  - 401 y 403 son un `ProblemDetail` genérico con `WWW-Authenticate: Bearer`, que no dice por qué se rechazó el token. También cuando no se pueden pedir las claves: ese caso se registra en el log, salvo los rechazos por el límite de recargas.
- **Quién actúa**: `shared.CurrentUser` devuelve el `sub` de un `JwtAuthenticationToken`, o vacío sin usuario (el sistema). Cualquier otra autenticación lanza un error: nunca se registra como `SYSTEM` algo que hizo una persona.
- **Límite de peticiones en dos fases**:
  - Antes de Spring Security, cada petición cuenta en el límite anónimo de su IP al llegar, y se devuelve al terminar si acabó con un usuario autenticado.
  - Después, cada usuario tiene su propio límite.

## Consecuencias

- **Lo que se gana**:
  - Un token falsificado, caducado o de otro emisor o audiencia no pasa, y la respuesta no ayuda a ajustarlo.
  - Cambiar de dominio o de realm es cuestión de variables.
  - Con Keycloak caído, la aplicación arranca y cada petición se rechaza en segundos.
  - Una avalancha de tokens falsos acaba en 429 sin que se validen sus firmas, también si las peticiones llegan a la vez.
  - Los usuarios de una clínica detrás de una misma IP conservan su propio límite.
- **Lo que cuesta**:
  - Si alguien agota el límite de una IP, sus usuarios legítimos también reciben 429 hasta que se recupere, porque antes de validar el token no hay forma de distinguirlos.
  - Con Keycloak caído, los 401 de los usuarios válidos gastan el límite de su IP.
  - Una petición autenticada hace tres llamadas a Redis (contar la IP, contar el usuario y devolver la IP).
  - Los tests no pueden usar `@WithMockUser` para lo que se audita: usan tokens reales (`TestTokens`, contra el JWKS local `TestJwks`) o `@WithJwtUser`.
- Un rol nuevo del realm no da nada hasta que el código lo conozca. Los roles dentro de cada clínica llegan con la membresía (M02-T03 y M02-T04).

## Alternativas descartadas

- **`issuer-uri` con descubrimiento OIDC**: pide la configuración a Keycloak al arrancar.
- **`NimbusJwtDecoder.withJwkSetUri` sin más**: sin límite de recargas, cada `kid` desconocido pediría las claves otra vez.
- **Límite por IP solo antes de Spring Security, contando también a los usuarios autenticados**: una clínica con NAT compartiría un único límite anónimo.
- **Comprobar el límite antes y contar al terminar**: con peticiones concurrentes, todas pasan la comprobación antes de que ninguna se cuente.
- **Registrar como `SYSTEM` las autenticaciones que no son de token**: atribuiría al sistema acciones de una persona.
