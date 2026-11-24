package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Guard for the multi-clinic rule of AGENTS.md, run on the schema after all migrations: every table except Flyway's
 * history is isolated by clinic (see {@link TenantIsolationInspector}), and the application role cannot escape Row
 * Level Security. A new table that skips {@code enable_tenant_isolation}, or brings a policy of its own, fails here.
 */
@IntegrationTest
class RowLevelSecurityGuardTests {

    private static final String SCHEMA = "public";

    /**
     * Tables whose policy compares a column other than {@code tenant_id}. Every entry needs a reason: {@code clinic} is
     * the tenant itself, so its rows are isolated by {@code id}.
     */
    private static final Map<String, String> TENANT_COLUMN_EXCEPTIONS = Map.of("clinic", "id");

    @Autowired
    private Flyway flyway;

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = connectAsApplication();
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    @Test
    void everyTableIsIsolatedByClinic() throws SQLException {
        TenantIsolationInspector inspector = new TenantIsolationInspector(
                connection,
                SCHEMA,
                TENANT_COLUMN_EXCEPTIONS,
                Set.of(flyway.getConfiguration().getTable()));

        assertThat(inspector.tables()).as("tables of schema %s", SCHEMA).contains("clinic");
        assertThat(inspector.problems())
                .as("tables not isolated by clinic; create them as described in docs/plan/M01-clinicas-multitenant.md")
                .isEmpty();
    }

    @Test
    void applicationRoleDoesNotBypassRowLevelSecurity() throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, APP_USER);
            try (ResultSet role = statement.executeQuery()) {
                assertThat(role.next()).isTrue();
                assertThat(role.getBoolean("rolsuper")).isFalse();
                assertThat(role.getBoolean("rolbypassrls")).isFalse();
            }
        }
    }

    @Test
    void applicationRoleOwnsNothing() throws SQLException {
        // Ownership of any object (table, view, sequence, function, type, schema, database) is an 'o' entry in
        // pg_shdepend, whichever database of the cluster the object lives in.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count(*) FROM pg_shdepend WHERE deptype = 'o'"
                        + " AND refclassid = 'pg_authid'::regclass"
                        + " AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = ?)")) {
            statement.setString(1, APP_USER);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }
}
