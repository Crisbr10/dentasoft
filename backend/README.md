# Backend de Dentasoft

Spring Boot 4 sobre Java 25, monolito modular con Spring Modulith. Paquete raíz `es.crisbr.dentasoft`.

## Comandos

```powershell
.\mvnw.cmd verify           # compila, pasa los tests y genera la documentación de módulos
.\mvnw.cmd spring-boot:run  # arranca en http://localhost:8080 con el perfil local
```

La documentación de módulos (PlantUML y AsciiDoc) queda en `target/spring-modulith-docs` tras ejecutar `ModularityTests`.

## Perfiles

| Perfil | Uso | OpenAPI y Swagger UI |
| --- | --- | --- |
| *(ninguno)* | Base segura por defecto | Desactivados |
| `local` | Desarrollo; lo activa `spring-boot:run` | `/v3/api-docs` y `/swagger-ui/index.html` |
| `prod` | Producción | Desactivados |

El jar empaquetado no activa ningún perfil por sí mismo: se elige con `SPRING_PROFILES_ACTIVE`.

## Variables de entorno

| Variable | Por defecto | Para qué |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | — | Perfil activo (`local`, `prod`) |
| `SERVER_PORT` | `8080` | Puerto HTTP |
| `SHUTDOWN_TIMEOUT` | `30s` | Tiempo máximo del apagado ordenado |
| `DENTASOFT_URLS_APP` | solo en `local`: `http://localhost:4200` | URL pública del frontend (`dentasoft.urls.app`) |
| `DENTASOFT_URLS_API` | solo en `local`: `http://localhost:8080` | URL pública del backend (`dentasoft.urls.api`); es el servidor del documento OpenAPI |
| `DENTASOFT_URLS_AUTH` | solo en `local`: `http://localhost:8180` | URL pública de Keycloak (`dentasoft.urls.auth`) |
| `DENTASOFT_CORS_ALLOWED_ORIGINS` | `dentasoft.urls.app` | Orígenes CORS permitidos, separados por comas (`dentasoft.cors.allowed-origins`); el filtro llega con M00-T08 |

`application.yml` no contiene secretos: cada valor que cambie por entorno se lee de una variable de entorno.

### URLs públicas

Los dominios son temporales y cambian por entorno, así que **ningún dominio ni URL pública se escribe en el código ni en `application*.yml` fuera del perfil `local`**. Las tres URLs viven en el record `shared.DentasoftUrls` (`@ConfigurationProperties("dentasoft.urls")`, validado). Fuera del perfil `local` son obligatorias: si falta alguna o no es una URL válida, la aplicación no arranca y el error indica la variable que falta:

```text
Property: dentasoft.urls.api
Reason: is required: set the DENTASOFT_URLS_API environment variable
```

`NoHardcodedUrlsTests` falla si encuentra `crisbr.es` o una URL `https://` en `src/main`.

## Endpoints de operación

- `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness` (sondas de Kubernetes).
- `/actuator/info`. No se expone ningún otro endpoint de Actuator.

## Errores

`shared.web.GlobalExceptionHandler` convierte cualquier error en `ProblemDetail` (`application/problem+json`). Los textos salen de `messages.properties`; ni el mensaje de la excepción ni la traza llegan al cliente. Los errores de validación incluyen `errors` con el campo y el mensaje, nunca el valor rechazado.
