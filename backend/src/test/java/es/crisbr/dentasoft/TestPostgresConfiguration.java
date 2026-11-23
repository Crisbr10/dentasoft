package es.crisbr.dentasoft;

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
    public static final String OWNER_USER = "dentasoft_owner";
    public static final String OWNER_PASSWORD = "test-only-owner-password";
    public static final String APP_USER = "dentasoft_app";
    public static final String APP_PASSWORD = "test-only-app-password";

    /** Run by the image on its first start, as in {@code infra/compose.yml}. Tests run from {@code backend/}. */
    private static final String INIT_SCRIPT = "../infra/postgres/init/01-init.sh";

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
                        MountableFile.forHostPath(INIT_SCRIPT, 0755), "/docker-entrypoint-initdb.d/01-init.sh");
    }

    /** JDBC URL of the {@code dentasoft} database, the one the application uses. */
    public static String jdbcUrl(PostgreSQLContainer container) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(container.getHost(), container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }
}
