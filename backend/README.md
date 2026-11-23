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

`application.yml` no contiene secretos: cada valor que cambie por entorno se lee de una variable de entorno.

## Endpoints de operación

- `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness` (sondas de Kubernetes).
- `/actuator/info`. No se expone ningún otro endpoint de Actuator.

## Errores

`shared.web.GlobalExceptionHandler` convierte cualquier error en `ProblemDetail` (`application/problem+json`). Los textos salen de `messages.properties`; ni el mensaje de la excepción ni la traza llegan al cliente. Los errores de validación incluyen `errors` con el campo y el mensaje, nunca el valor rechazado.
