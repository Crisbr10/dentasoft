package es.crisbr.dentasoft.platform;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.PLATFORM_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestPostgresConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The platform role {@code dentasoft_platform} (ADR 0011) has exactly what clinic administration needs, and the
 * application role cannot become it. Read from the catalog, so a later migration that grants it more fails here.
 */
@IntegrationTest
class PlatformRoleTests {

    /** Every privilege the role holds, as {@code table: PRIVILEGE} or {@code table.column: PRIVILEGE}. */
    private static final Set<String> EXPECTED_PRIVILEGES = Set.of(
            "clinic: SELECT",
            "clinic.id: INSERT",
            "clinic.name: INSERT",
            "clinic.legal_name: INSERT",
            "clinic.tax_id: INSERT",
            "clinic.timezone: INSERT",
            "clinic.locale: INSERT",
            "clinic.status: INSERT",
            "clinic.created_at: INSERT",
            "clinic.created_by: INSERT",
            "clinic.updated_at: INSERT",
            "clinic.updated_by: INSERT",
            "clinic.version: INSERT",
            "clinic.status: UPDATE",
            "clinic.updated_at: UPDATE",
            "clinic.updated_by: UPDATE",
            "clinic.version: UPDATE",
            "clinic_settings.id: INSERT",
            "clinic_settings.tenant_id: INSERT",
            "clinic_settings.created_at: INSERT",
            "clinic_settings.created_by: INSERT",
            "clinic_settings.updated_at: INSERT",
            "clinic_settings.updated_by: INSERT",
            "clinic_settings.version: INSERT",
            "audit_log.id: INSERT",
            "audit_log.tenant_id: INSERT",
            "audit_log.actor_type: INSERT",
            "audit_log.actor_id: INSERT",
            "audit_log.action: INSERT",
            "audit_log.entity_type: INSERT",
            "audit_log.entity_id: INSERT",
            "audit_log.outcome: INSERT",
            "audit_log.request_id: INSERT",
            "audit_log.details: INSERT");

    @Test
    void isAnOrdinaryRoleThatDoesNotBypassRowLevelSecurity() throws SQLException {
        try (Connection connection = connectAsApplication();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolreplication, rolinherit,"
                                + " rolcanlogin FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, PLATFORM_USER);
            try (ResultSet role = statement.executeQuery()) {
                assertThat(role.next()).as("role %s exists", PLATFORM_USER).isTrue();
                assertThat(role.getBoolean("rolsuper")).isFalse();
                assertThat(role.getBoolean("rolbypassrls")).isFalse();
                assertThat(role.getBoolean("rolcreaterole")).isFalse();
                assertThat(role.getBoolean("rolcreatedb")).isFalse();
                assertThat(role.getBoolean("rolreplication")).isFalse();
                assertThat(role.getBoolean("rolinherit")).isFalse();
                assertThat(role.getBoolean("rolcanlogin")).isTrue();
            }
        }
    }

    /** No SET ROLE either way: the application role is not a member of it, and it is a member of nothing. */
    @Test
    void hasNoMembershipsInEitherDirection() throws SQLException {
        assertThat(count(
                        "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid"
                                + " JOIN pg_roles u ON u.oid = m.member WHERE r.rolname = ? OR u.rolname = ?",
                        PLATFORM_USER,
                        PLATFORM_USER))
                .isZero();
        try (Connection connection = connectAsApplication()) {
            assertThatThrownBy(() -> execute(connection, "SET ROLE " + PLATFORM_USER))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
        assertThat(count(
                        "SELECT count(*) FROM pg_roles WHERE pg_has_role(?, oid, 'MEMBER') AND rolname = ?",
                        APP_USER,
                        PLATFORM_USER))
                .isZero();
    }

    @Test
    void ownsNothing() throws SQLException {
        assertThat(count(
                        "SELECT count(*) FROM pg_shdepend WHERE deptype = 'o' AND refclassid = 'pg_authid'::regclass"
                                + " AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = ?)",
                        PLATFORM_USER))
                .isZero();
    }

    /** Table and column privileges in every schema of the database, compared in full. */
    @Test
    void holdsExactlyThePrivilegesOfClinicAdministration() throws SQLException {
        Set<String> privileges = new HashSet<>();
        try (Connection connection = connectAsApplication()) {
            collect(
                    connection,
                    "SELECT c.relname AS object, a.privilege_type FROM pg_class c, aclexplode(c.relacl) a"
                            + " WHERE a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)",
                    privileges);
            collect(
                    connection,
                    "SELECT c.relname || '.' || att.attname AS object, a.privilege_type FROM pg_attribute att"
                            + " JOIN pg_class c ON c.oid = att.attrelid, aclexplode(att.attacl) a"
                            + " WHERE a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)",
                    privileges);
        }

        assertThat(privileges).containsExactlyInAnyOrderElementsOf(EXPECTED_PRIVILEGES);
    }

    /** The schema's own functions ({@code enable_tenant_isolation}…), not the extensions' ({@code vector}). */
    @Test
    void cannotExecuteTheSchemaFunctions() throws SQLException {
        assertThat(count(
                        "SELECT count(*) FROM pg_proc p WHERE p.proowner = (SELECT oid FROM pg_roles WHERE rolname = ?)"
                                + " AND has_function_privilege(?, p.oid, 'EXECUTE')",
                        TestPostgresConfiguration.OWNER_USER,
                        PLATFORM_USER))
                .isZero();
        assertThat(count("SELECT count(*) FROM pg_proc WHERE proname = 'enable_tenant_isolation'"))
                .isEqualTo(1);
    }

    /** What the privileges above mean in practice: it can never read a clinic's settings nor touch other data. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM clinic_settings",
                "UPDATE clinic_settings SET default_appointment_minutes = 60",
                "INSERT INTO clinic_settings (id, tenant_id, default_appointment_minutes, created_at, updated_at)"
                        + " VALUES (gen_random_uuid(), gen_random_uuid(), 60, now(), now())",
                "UPDATE clinic SET name = 'Renombrada'",
                "UPDATE clinic SET keycloak_org_id = 'org'",
                "DELETE FROM clinic",
                "DELETE FROM clinic_settings",
                "TRUNCATE clinic",
                "SELECT * FROM tenancy_test_record",
                "SELECT * FROM flyway_schema_history",
                "CREATE TABLE platform_probe (id int)"
            })
    void isRefusedEverythingElse(String sql) throws SQLException {
        try (Connection connection = connectAsPlatform()) {
            assertThatThrownBy(() -> execute(connection, sql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }

    /** The platform sees a clinic without any active clinic; the application, without one, still sees none. */
    @Test
    void seesEveryClinicWhileTheApplicationStillSeesNone() throws SQLException {
        UUID clinic = UUID.randomUUID();
        try (Connection platform = connectAsPlatform()) {
            try (PreparedStatement insert = platform.prepareStatement(
                    "INSERT INTO clinic (id, name, legal_name, tax_id, created_at, updated_at)"
                            + " VALUES (?, 'Clínica Visible', 'Clínica Visible S.L.', 'B1', now(), now())")) {
                insert.setObject(1, clinic);
                insert.executeUpdate();
            }
            assertThat(countById(platform, clinic)).isEqualTo(1);
            try (Connection application = connectAsApplication()) {
                assertThat(countById(application, clinic)).isZero();
            }
        } finally {
            PlatformTestDatabase.deleteClinics(List.of(clinic));
        }
    }

    private static long countById(Connection connection, UUID clinic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM clinic WHERE id = ?")) {
            statement.setObject(1, clinic);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void collect(Connection connection, String sql, Set<String> into) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PLATFORM_USER);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    into.add(result.getString("object") + ": " + result.getString("privilege_type"));
                }
            }
        }
    }

    /** As the application role, which can read the catalog but no clinic without an active one. */
    private static long count(String sql, String... parameters) throws SQLException {
        try (Connection connection = connectAsApplication();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
