# Backend image: Maven build, then a JRE 25 runtime with the Spring Boot layers.
#   docker build -t dentasoft-backend:local backend
# Base images are pinned by tag and digest. Dependabot updates the digests:
# never replace them by hand with a floating tag such as `latest`.

FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS build
WORKDIR /build
# The Maven distribution, the plugins and the dependencies go in a layer of their own that only
# depends on the wrapper and pom.xml, so a code change reuses it and downloads nothing. They stay
# in the layer, not in a cache mount: the CI keeps layers between runs (docs/operaciones/
# integracion-continua.md), but never the contents of a cache mount.
# The layer runs the same build as below on a placeholder application, so Maven downloads exactly
# what that build needs; `dependency:go-offline` misses some of it, such as the compiler's
# annotation processors.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN mkdir -p src/main/java src/test/java \
    && echo 'class Placeholder { public static void main(String[] args) {} }' > src/main/java/Placeholder.java \
    && echo 'class PlaceholderTests {}' > src/test/java/PlaceholderTests.java \
    && sh ./mvnw --batch-mode --quiet package -DskipTests \
    && rm -rf src target
COPY src/ src/
# Tests are not run here: `mvnw verify` runs them before any image is built (locally and in CI).
# The build has no network: if the layer above missed something, it fails here instead of
# downloading it on every build.
RUN --network=none \
    sh ./mvnw --batch-mode --quiet --offline package -DskipTests \
    && cp target/dentasoft-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99
# Unprivileged user with a fixed UID, no home directory and no login shell.
RUN groupadd --system --gid 10001 dentasoft \
    && useradd --system --uid 10001 --gid dentasoft --no-create-home \
       --home-dir /nonexistent --shell /usr/sbin/nologin dentasoft
WORKDIR /app
# From the least to the most frequently changed layer, so a code change only rebuilds the last one.
# The files stay owned by root: the application cannot modify itself.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# The image has no curl or wget: query the readiness probe with bash's /dev/tcp.
# Readiness answers {"status":"UP"} with 200, or OUT_OF_SERVICE with 503.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
# Exec form: java is PID 1 and receives SIGTERM, so the graceful shutdown runs.
#   MaxRAMPercentage=75     heap sized from the container memory limit, leaving room for the rest
#   ExitOnOutOfMemoryError  exit (and let the orchestrator restart) instead of staying half alive
#   java.io.tmpdir, user.home  the user has no writable home: nothing may write outside /tmp
# Extra JVM options can be passed with JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-jar", "application.jar"]
