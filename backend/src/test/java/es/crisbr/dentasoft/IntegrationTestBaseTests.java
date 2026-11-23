package es.crisbr.dentasoft;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_PASSWORD;
import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Example of {@link IntegrationTest}: the containers start and PostgreSQL is prepared as in {@code infra/}. */
@IntegrationTest
class IntegrationTestBaseTests {

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void applicationRoleIsNeitherSuperuserNorBypassesRowLevelSecurity() throws SQLException {
        try (Connection connection = connectAsApplication();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
                ResultSet role = statement.executeQuery()) {
            assertThat(role.next()).isTrue();
            assertThat(role.getBoolean("rolsuper")).isFalse();
            assertThat(role.getBoolean("rolbypassrls")).isFalse();
        }
    }

    @Test
    void vectorExtensionIsInstalled() throws SQLException {
        try (Connection connection = connectAsApplication();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM pg_extension WHERE extname = ?")) {
            statement.setString(1, "vector");
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void applicationRoleCannotCreateTables() throws SQLException {
        try (Connection connection = connectAsApplication();
                PreparedStatement statement = connection.prepareStatement("CREATE TABLE intruder (id int)")) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(statement::execute)
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void redisIsAvailable(@Autowired StringRedisTemplate redis) {
        String key = "integration-test:" + TestData.CLINIC_A.id();

        redis.opsForValue().set(key, TestData.person(0).email());

        assertThat(redis.opsForValue().get(key)).isEqualTo(TestData.person(0).email());
        redis.delete(key);
    }

    private Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(TestPostgresConfiguration.jdbcUrl(postgres), APP_USER, APP_PASSWORD);
    }
}
