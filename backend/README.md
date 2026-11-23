# Backend de Dentasoft

Spring Boot 4 sobre Java 25, monolito modular con Spring Modulith. Paquete raíz `es.crisbr.dentasoft`.

## Comandos

```powershell
.\mvnw.cmd verify           # compila, pasa los tests y genera la documentación de módulos
.\mvnw.cmd spring-boot:run  # arranca en http://localhost:8080 con el perfil local
```

La documentación de módulos (PlantUML y AsciiDoc) queda en `target/spring-modulith-docs` tras ejecutar `ModularityTests`.

## Imagen Docker

`Dockerfile` multi-etapa: compila con el JDK 25 y Maven Wrapper (sin tests: los pasa `mvnw verify` antes) y ejecuta sobre el JRE 25 de Eclipse Temurin (Ubuntu 24.04).

```powershell
docker build -t dentasoft-backend:local .
```

Para arrancarla junto a la infraestructura local, usa el perfil `app` de Compose (ver [infra/README.md](../infra/README.md#backend-en-contenedor-opcional)).

- **Capas de Spring Boot**: el jar se extrae con `-Djarmode=tools extract --layers` en `dependencies`, `spring-boot-loader`, `snapshot-dependencies` y `application`. Un cambio de código solo reconstruye la última capa.
- **Usuario sin privilegios**: UID y GID `10001`, sin home ni shell. Los ficheros de la aplicación son de `root`: el proceso no puede modificarlos.
- **Proceso principal**: `ENTRYPOINT` en forma exec, así que Java es el PID 1 y recibe el `SIGTERM` del apagado ordenado.
- **Opciones de la JVM**: `-XX:MaxRAMPercentage=75` (heap según el límite de memoria del contenedor), `-XX:+ExitOnOutOfMemoryError` (el contenedor se reinicia en lugar de quedarse medio vivo) y `-Djava.io.tmpdir=/tmp -Duser.home=/tmp` (nada escribe fuera de `/tmp`). Otras opciones se pasan con `JAVA_TOOL_OPTIONS`.
- **Health check**: consulta `/actuator/health/readiness` con `/dev/tcp` de bash, porque la imagen no trae `curl` ni `wget`.
- **Imágenes base fijadas por etiqueta y digest**. Los digests los actualizará Dependabot cuando llegue M00-T07: no los cambies a mano por una etiqueta flotante como `latest`.

### Escaneo de vulnerabilidades

Con Trivy en contenedor. El primer comando falla solo con vulnerabilidades críticas; el segundo enseña también las altas, que conviene revisar (las imágenes base de Ubuntu suelen traer alguna sin parche):

```powershell
$trivy = "run", "--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock", "-v", "trivy-cache:/root/.cache/trivy", "aquasec/trivy:0.76.2"
docker @trivy image --scanners vuln --severity CRITICAL --exit-code 1 dentasoft-backend:local
docker @trivy image --scanners vuln --severity HIGH,CRITICAL dentasoft-backend:local
```

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
