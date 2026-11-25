package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Both connections, the application's and the platform's, leave the detail of PostgreSQL's errors out of the message
 * of the exception (#123): the detail repeats the values of the row, which may be personal data, and exception
 * messages end up in logs. The detail stays in the exception's server error, for code that needs its fields.
 */
@IntegrationTest
class DatabaseErrorDetailTests {

    /** Invented. */
    private static final String VALUE = "ana.zafiro.7731@ejemplo.test";

    private static final String RAISE_WITH_DETAIL = """
            DO $$
            BEGIN
                RAISE unique_violation USING
                    MESSAGE = 'error detail probe',
                    DETAIL = 'Key (email)=(ana.zafiro.7731@ejemplo.test) already exists.',
                    CONSTRAINT = 'error_detail_probe_key';
            END
            $$""";

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("platformDataSource")
    private DataSource platformDataSource;

    @Test
    void theApplicationConnectionLeavesTheValueOutOfTheMessage() throws SQLException {
        assertLeavesTheValueOutOfTheMessage(dataSource);
    }

    @Test
    void thePlatformConnectionLeavesTheValueOutOfTheMessage() throws SQLException {
        assertLeavesTheValueOutOfTheMessage(platformDataSource);
    }

    /**
     * An error raised with a detail, as PostgreSQL gives one for a unique key violation. A block rather than a table:
     * the roles may not create temporary tables, and the value is a constant of this test, not a parameter.
     */
    private static void assertLeavesTheValueOutOfTheMessage(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            PSQLException error = catchThrowableOfType(PSQLException.class, () -> statement.execute(RAISE_WITH_DETAIL));

            assertThat(error.getMessage()).contains("error detail probe").doesNotContain(VALUE);
            assertThat(error.getServerErrorMessage()).isNotNull();
            assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("error_detail_probe_key");
            assertThat(error.getServerErrorMessage().getDetail())
                    .isEqualTo("Key (email)=(" + VALUE + ") already exists.");
        }
    }
}
