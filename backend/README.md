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
| `DENTASOFT_CORS_ALLOWED_ORIGINS` | `dentasoft.urls.app` | Orígenes CORS permitidos, separados por comas (`dentasoft.cors.allowed-origins`) |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `localhost`, `6379` | Redis, donde se guardan los límites de peticiones |
| `SPRING_DATA_REDIS_PASSWORD` | en `local`: `DENTASOFT_REDIS_PASSWORD` | Contraseña de Redis |
| `DENTASOFT_RATE_LIMIT_KEY` | solo en `local`: un valor de desarrollo | Clave secreta (32 caracteres o más) del HMAC con el que se guardan IPs y usuarios en Redis. **Obligatoria fuera de `local`** |
| `DENTASOFT_RATE_LIMIT_ANONYMOUS_PER_MINUTE` | `120` | Peticiones por minuto de cada IP sin usuario (`dentasoft.rate-limit.anonymous-per-minute`) |
| `DENTASOFT_RATE_LIMIT_USER_PER_MINUTE` | `600` | Peticiones por minuto de cada usuario autenticado (`dentasoft.rate-limit.user-per-minute`) |
| `DENTASOFT_RATE_LIMIT_ENABLED` | `true` | Desactiva el límite (solo para diagnosticar problemas); desactivado, no hace falta `DENTASOFT_RATE_LIMIT_KEY` |
| `DENTASOFT_TRUSTED_PROXIES` | *(vacía)* | IPs o rangos CIDR de los proxies de confianza, separados por comas (`dentasoft.http.trusted-proxies`) |
| `DENTASOFT_HTTP_MAX_REQUEST_BODY_SIZE` | `1MB` | Tamaño máximo del cuerpo de las peticiones que no son multipart (`dentasoft.http.max-request-body-size`) |
| `DENTASOFT_LOG_FORMAT` | `ecs`; en `local`, texto | Formato de los logs: `ecs`, `logstash`, `gelf` o vacío para texto |

`application.yml` no contiene secretos: cada valor que cambie por entorno se lee de una variable de entorno.

### URLs públicas

Los dominios son temporales y cambian por entorno, así que **ningún dominio ni URL pública se escribe en el código ni en `application*.yml` fuera del perfil `local`**. Las tres URLs viven en el record `shared.DentasoftUrls` (`@ConfigurationProperties("dentasoft.urls")`, validado). Fuera del perfil `local` son obligatorias: si falta alguna o no es una URL válida, la aplicación no arranca y el error indica la variable que falta:

```text
Property: dentasoft.urls.api
Reason: is required: set the DENTASOFT_URLS_API environment variable
```

`NoHardcodedUrlsTests` falla si encuentra `crisbr.es` o una URL `https://` en `src/main`.

## Seguridad transversal

Filtros de `shared.web`, en este orden: ID de petición, cabeceras de seguridad, CORS, tamaño máximo, (Spring Security, desde M02-T02) y límite de peticiones.

- **ID de petición**: cada petición lleva un `X-Request-Id`, que va al MDC (`requestId`) y vuelve en la respuesta. El que manda el cliente solo se acepta si tiene de 1 a 64 caracteres de `[A-Za-z0-9._-]`; si no, se genera uno nuevo y nunca se devuelve el recibido.
- **Cabeceras**: CSP `default-src 'none'` (Swagger UI recibe la mínima para funcionar), `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS, `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy`, `Permissions-Policy` y `Cache-Control: no-store`.
- **CORS** en `/api/**` y `/actuator/health/**`, solo para `DENTASOFT_CORS_ALLOWED_ORIGINS`. Sin credenciales (la API usa token Bearer). Cabeceras permitidas: `Authorization`, `Content-Type`, `X-Clinic-Id` y `X-Request-Id`; expuestas: `X-Request-Id` y `Retry-After`. El preflight se cachea una hora.
- **Tamaño máximo**: un cuerpo de más de `DENTASOFT_HTTP_MAX_REQUEST_BODY_SIZE` devuelve 413 con `ProblemDetail`, también si llega sin `Content-Length`. Las subidas multipart se limitan con `spring.servlet.multipart.*`.
- **Límite de peticiones** (Bucket4j sobre Redis): por usuario si la petición está autenticada y por IP si es anónima. Superarlo devuelve 429 con `ProblemDetail` y `Retry-After`. Las sondas `/actuator/health/liveness` y `/readiness` no cuentan. Las claves de Redis son HMAC-SHA256 con `DENTASOFT_RATE_LIMIT_KEY`: nunca guardan la IP ni el usuario en claro. Las IPv6 se agrupan por su prefijo /64.
- **IP del cliente**: se usa la de la conexión. `CF-Connecting-IP` y `X-Forwarded-For` solo se leen si la conexión viene de un proxy de `DENTASOFT_TRUSTED_PROXIES`; en `X-Forwarded-For` el cliente es el primer salto que no es de confianza, leyendo de derecha a izquierda. Pon en la lista solo proxies que reescriben esas cabeceras.
- **Si Redis no responde**, las peticiones no se bloquean: se aplica el mismo límite en memoria en cada instancia y cada 5 segundos una sola petición vuelve a probar Redis, y las demás no esperan. Se escribe un aviso como mucho una vez por minuto, y la métrica `dentasoft.rate.limit.redis.available` baja a 0 (también hay `dentasoft.rate.limit.fallback`, `dentasoft.rate.limit.fallback.evicted` y `dentasoft.rate.limit.rejected`). `/actuator/health` no incluye Redis (`management.health.redis.enabled: false`), así que una caída de Redis no lo pone en `DOWN`; si más adelante Redis pasa a ser imprescindible, por ejemplo para colas, habrá que volver a incluirlo.

### Logs

En JSON, un objeto por línea ([Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/)), con el campo `requestId` en cada línea de una petición. En `local` se escriben en texto, con el ID entre corchetes. **Nunca se registran datos personales ni clínicos**: usa identificadores. Los errores 500 se registran con las clases y la traza de la excepción, pero sin sus mensajes, que pueden contener los datos que causaron el error.

## Endpoints de operación

- `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness` (sondas de Kubernetes).
- `/actuator/info`. No se expone ningún otro endpoint de Actuator.

## Errores

`shared.web.GlobalExceptionHandler` convierte cualquier error en `ProblemDetail` (`application/problem+json`). Los textos salen de `messages.properties`; ni el mensaje de la excepción ni la traza llegan al cliente. Los errores de validación incluyen `errors` con el campo y el mensaje, nunca el valor rechazado.
