package es.crisbr.dentasoft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL for every test that starts the application, with the same image ({@code POSTGRES_IMAGE}) and the same
 * init script as {@code infra/}: databases, the {@code dentasoft_owner} and {@code dentasoft_app} roles and the
 * {@code vector} extension.
 *
 * <p>The application connects as {@code dentasoft_app} and Flyway migrates as {@code dentasoft_owner}, as in every
 * environment. A {@code @ServiceConnection} is not used on purpose: it would connect the application with the
 * container's superuser, which bypasses Row Level Security and would hide any isolation bug.
 *
 * <p>One container is shared by every Spring context of the test run (contexts differ in properties, not in the
 * database) and it is stopped when the JVM exits. Flyway migrates it once; later contexts find it up to
 * date.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPostgresConfiguration {

    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres");

    public static final String DATABASE = "dentasoft";
    public static final String APP_USER = "dentasoft_app";
    public static final String OWNER_USER = "dentasoft_owner";
    public static final String PLATFORM_USER = "dentasoft_platform";
    private static final String APP_PASSWORD = "test-only-app-password";
    private static final String OWNER_PASSWORD = "test-only-owner-password";
    private static final String PLATFORM_PASSWORD = "test-only-platform-password";

    /** Run by the image on its first start, in this order, as in {@code infra/compose.yml}. */
    private static final Path INIT_SCRIPTS = Path.of("infra", "postgres", "init");

    private static final List<String> INIT_SCRIPT_NAMES = List.of("01-init.sh", "02-platform-role.sh");

    private static PostgreSQLContainer container;

    @Bean
    DynamicPropertyRegistrar postgresProperties() {
        String url = jdbcUrl();
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", () -> OWNER_USER);
            registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
            registry.add("dentasoft.platform.datasource.url", () -> url);
            registry.add("dentasoft.platform.datasource.username", () -> PLATFORM_USER);
            registry.add("dentasoft.platform.datasource.password", () -> PLATFORM_PASSWORD);
            // Spring keeps every context of the run cached, each with its own pool, against this one database:
            // small pools keep them under PostgreSQL's max_connections (100).
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
            registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        };
    }

    /** JDBC URL of the {@code dentasoft} database, the one the application uses. Starts the container if needed. */
    public static String jdbcUrl() {
        PostgreSQLContainer postgres = container();
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }

    /** A connection as the application role, subject to Row Level Security. */
    public static Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
    }

    /** A connection as the platform role, which sees every clinic through its own policies (ADR 0011). */
    public static Connection connectAsPlatform() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), PLATFORM_USER, PLATFORM_PASSWORD);
    }

    /** A connection as the role that owns the schema and runs the migrations. */
    public static Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withUsername("postgres")
                    .withPassword("test-only-superuser-password")
                    .withDatabaseName("postgres")
                    .withEnv("DENTASOFT_DB_OWNER_PASSWORD", OWNER_PASSWORD)
                    .withEnv("DENTASOFT_DB_APP_PASSWORD", APP_PASSWORD)
                    .withEnv("KEYCLOAK_DB_PASSWORD", "test-only-keycloak-password")
                    .withEnv("DENTASOFT_DB_PLATFORM_PASSWORD", PLATFORM_PASSWORD)
                    .withEnv("TZ", "UTC");
            for (String name : INIT_SCRIPT_NAMES) {
                postgres.withCopyFileToContainer(
                        MountableFile.forHostPath(initScripts().resolve(name), 0755),
                        "/docker-entrypoint-initdb.d/" + name);
            }
            postgres.start();
            // Stops it at JVM exit even where the Testcontainers reaper (Ryuk) is disabled.
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop, "postgres-test-container-stop"));
            container = postgres;
        }
        return container;
    }

    /**
     * Finds the init scripts from the working directory or one of its parents, so the tests work whether they run
     * from {@code backend/} (Maven) or from the repository root (some IDEs).
     */
    static Path initScripts() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path scripts = dir.resolve(INIT_SCRIPTS);
            if (Files.isRegularFile(scripts.resolve(INIT_SCRIPT_NAMES.getFirst()))) {
                return scripts;
            }
        }
        throw new IllegalStateException(
                INIT_SCRIPTS + " not found from " + Path.of("").toAbsolutePath() + " or its parents");
    }
}
