package es.crisbr.dentasoft;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL for integration tests, with the same image ({@code POSTGRES_IMAGE}) and the same init script as
 * {@code infra/}: databases, the {@code dentasoft_owner} and {@code dentasoft_app} roles and the {@code vector}
 * extension. Spring stops it with the context.
 *
 * <p>The application has no JDBC yet, so tests connect with {@link #jdbcUrl}. M01-T01 adds JDBC, Flyway and the
 * service connection.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPostgresConfiguration {

    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres");

    public static final String DATABASE = "dentasoft";
    public static final String APP_USER = "dentasoft_app";
    public static final String APP_PASSWORD = "test-only-app-password";
    private static final String OWNER_PASSWORD = "test-only-owner-password";

    /** Run by the image on its first start, as in {@code infra/compose.yml}. */
    private static final Path INIT_SCRIPT = Path.of("infra", "postgres", "init", "01-init.sh");

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withUsername("postgres")
                .withPassword("test-only-superuser-password")
                .withDatabaseName("postgres")
                .withEnv("DENTASOFT_DB_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("DENTASOFT_DB_APP_PASSWORD", APP_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", "test-only-keycloak-password")
                .withEnv("TZ", "UTC")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initScript(), 0755), "/docker-entrypoint-initdb.d/01-init.sh");
    }

    /**
     * Finds the init script from the working directory or one of its parents, so the tests work whether they run
     * from {@code backend/} (Maven) or from the repository root (some IDEs).
     */
    static Path initScript() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path script = dir.resolve(INIT_SCRIPT);
            if (Files.isRegularFile(script)) {
                return script;
            }
        }
        throw new IllegalStateException(
                INIT_SCRIPT + " not found from " + Path.of("").toAbsolutePath() + " or its parents");
    }

    /** JDBC URL of the {@code dentasoft} database, the one the application uses. */
    public static String jdbcUrl(PostgreSQLContainer container) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(container.getHost(), container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }
}
