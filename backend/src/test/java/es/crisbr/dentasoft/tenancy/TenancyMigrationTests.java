package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.OWNER_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V001 and V003: who migrates and who connects, the fail-closed {@code tenant_isolation} policy on {@code clinic} and
 * the privileges of the application role. Clinics A and B exist during each test.
 *
 * <p>The application cannot create clinics (V003), so the policy's {@code WITH CHECK} on {@code clinic} is checked as
 * the owner, who is subject to Row Level Security too (FORCE).
 */
@IntegrationTest
class TenancyMigrationTests {

    /** PostgreSQL error code {@code insufficient_privilege}, also raised when a row breaks a policy. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** PostgreSQL error code {@code invalid_text_representation}, raised when a value cannot be cast. */
    private static final String INVALID_TEXT_REPRESENTATION = "22P02";

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private Flyway flyway;

    /** As the owner: the application cannot create clinics. */
    @BeforeEach
    void createClinics() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setActiveClinic(connection, CLINIC_A.toString());
            insertClinic(connection, CLINIC_A, TestData.CLINIC_A.name());
            setActiveClinic(connection, CLINIC_B.toString());
            insertClinic(connection, CLINIC_B, TestData.CLINIC_B.name());
        }
    }

    /** The application cannot delete; the owner can, but only the active clinic's row, as RLS is forced. */
    @AfterEach
    void deleteClinics() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            for (UUID clinic : List.of(CLINIC_A, CLINIC_B)) {
                setActiveClinic(connection, clinic.toString());
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM clinic WHERE id = ?")) {
                    statement.setObject(1, clinic);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    void applicationConnectsAsApplicationRole(@Autowired JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(APP_USER);
    }

    @Test
    void migrationsRunAsOwnerRole() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            assertThat(strings(
                            connection,
                            "SELECT DISTINCT installed_by FROM "
                                    + flyway.getConfiguration().getTable()))
                    .containsExactly(OWNER_USER);
            assertThat(strings(connection, "SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'"))
                    .containsExactly(OWNER_USER);
        }
    }

    @Test
    void flywayCleanIsDisabled() {
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void withoutActiveClinicNoRowIsVisible() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            assertThat(visibleClinics(connection)).isEmpty();
        }
    }

    @Test
    void withResetClinicNoRowIsVisible() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, CLINIC_A.toString());
            setActiveClinic(connection, "");

            assertThat(visibleClinics(connection)).isEmpty();
        }
    }

    /** The clinic is set per transaction (M01-T03): once it ends, the connection is back to no clinic. */
    @Test
    void transactionScopedClinicIsGoneAfterTheTransaction() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            for (boolean commit : List.of(true, false)) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement =
                        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    statement.setString(1, CLINIC_A.toString());
                    statement.execute();
                }
                assertThat(visibleClinics(connection)).containsExactly(CLINIC_A.toString());
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                connection.setAutoCommit(true);

                assertThat(visibleClinics(connection))
                        .as("after %s", commit ? "commit" : "rollback")
                        .isEmpty();
            }
        }
    }

    /** Fails closed, but loudly: a malformed clinic never matches and every query on the table errors. */
    @Test
    void clinicThatIsNotAUuidRaisesAnError() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, "not-a-uuid");

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> visibleClinics(connection))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INVALID_TEXT_REPRESENTATION));
        }
    }

    @Test
    void activeClinicSeesOnlyItself() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, CLINIC_A.toString());

            assertThat(visibleClinics(connection)).containsExactly(CLINIC_A.toString());
        }
    }

    @Test
    void activeClinicCannotWriteAnotherClinic() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, CLINIC_A.toString());

            assertThat(updateName(connection, CLINIC_B)).isZero();
        }
        try (Connection connection = connectAsOwner()) {
            setActiveClinic(connection, CLINIC_A.toString());

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertClinic(connection, UUID.randomUUID(), "Clínica Prueba Intrusa"))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> moveClinic(connection, CLINIC_A, UUID.randomUUID()))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }

    @Test
    void withoutActiveClinicNothingCanBeWritten() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            assertThat(updateName(connection, CLINIC_A)).isZero();
        }
        try (Connection connection = connectAsOwner()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertClinic(connection, UUID.randomUUID(), "Clínica Prueba Huérfana"))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }

    /** Not even its own: creating clinics is the platform's (V002, V003). */
    @Test
    void applicationCannotCreateClinics() throws SQLException {
        UUID newClinic = UUID.randomUUID();
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, newClinic.toString());

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertClinic(connection, newClinic, "Clínica Prueba Nueva"))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }

    /**
     * The clinic only renames itself and changes its time zone (V003): it cannot reactivate itself, take another
     * Keycloak organization, change its tax data or its id, nor rewrite its audit columns.
     */
    @Test
    void applicationUpdatesOnlyTheColumnsTheClinicEdits() throws SQLException {
        try (Connection owner = connectAsOwner()) {
            assertThat(strings(
                            owner,
                            "SELECT attname FROM pg_attribute WHERE attrelid = 'clinic'::regclass AND attnum > 0"
                                    + " AND NOT attisdropped AND has_column_privilege('" + APP_USER
                                    + "', 'clinic', attname, 'UPDATE') ORDER BY attname"))
                    .containsExactly("name", "timezone", "updated_at", "updated_by", "version");
        }
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, CLINIC_A.toString());

            for (String change : List.of(
                    "status = 'ACTIVE'",
                    "keycloak_org_id = 'org-prueba'",
                    "legal_name = 'Otra Razón Social S.L.'",
                    "tax_id = 'B99999999'",
                    "locale = 'en-GB'",
                    "id = id",
                    "created_at = now()",
                    "created_by = NULL")) {
                assertInsufficientPrivilege(connection, "UPDATE clinic SET " + change);
            }
            assertThat(updateName(connection, CLINIC_A)).isOne();
            assertThat(update(connection, "UPDATE clinic SET timezone = 'Atlantic/Canary' WHERE id = ?", CLINIC_A))
                    .isOne();
        }
    }

    /**
     * Opening hours are replaced whole and are not clinical data, so their table is the only one where the
     * application deletes (V003). Every other table, the test ones included, keeps clinical data's soft delete.
     */
    @Test
    void applicationDeletesOnlyOpeningHours() throws SQLException {
        try (Connection owner = connectAsOwner()) {
            String tables = "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                    + " AND has_table_privilege('" + APP_USER + "', c.oid, '%s') ORDER BY c.relname";

            assertThat(strings(owner, tables.formatted("DELETE"))).containsExactly("clinic_hours_of_operation");
            assertThat(strings(owner, tables.formatted("TRUNCATE"))).isEmpty();
        }
    }

    /** FORCE ROW LEVEL SECURITY: migrations that touch data must set the clinic too (see the module plan). */
    @Test
    void ownerIsAlsoSubjectToRowLevelSecurity() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            assertThat(visibleClinics(connection)).isEmpty();

            setActiveClinic(connection, CLINIC_B.toString());
            assertThat(visibleClinics(connection)).containsExactly(CLINIC_B.toString());
        }
    }

    /**
     * The data-migration pattern of the module plan ("Migraciones que tocan datos"): without an active clinic an
     * UPDATE silently touches nothing; looping over the clinics with {@code set_config} reaches every one. Runs in a
     * transaction that is rolled back, as Flyway would undo a failed migration.
     */
    @Test
    void dataMigrationsMustLoopOverClinics() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            connection.setAutoCommit(false);
            try {
                execute(
                        connection,
                        "CREATE TABLE migration_probe (tenant_id uuid NOT NULL, migrated boolean NOT NULL)");
                execute(connection, "SELECT enable_tenant_isolation('migration_probe')");
                for (UUID clinic : List.of(CLINIC_A, CLINIC_B)) {
                    setActiveClinic(connection, clinic.toString());
                    execute(
                            connection,
                            "INSERT INTO migration_probe VALUES (current_setting('app.tenant_id')::uuid, false)");
                }
                setActiveClinic(connection, "");

                assertThat(update(connection, "UPDATE migration_probe SET migrated = true"))
                        .isZero();

                execute(connection, """
                        DO
                        $$
                            DECLARE
                                clinic_id uuid;
                            BEGIN
                                ALTER TABLE clinic NO FORCE ROW LEVEL SECURITY;
                                FOR clinic_id IN SELECT id FROM clinic
                                    LOOP
                                        PERFORM set_config('app.tenant_id', clinic_id::text, true);
                                        UPDATE migration_probe SET migrated = true;
                                    END LOOP;
                                ALTER TABLE clinic FORCE ROW LEVEL SECURITY;
                                PERFORM set_config('app.tenant_id', '', true);
                            END
                        $$""");

                for (UUID clinic : List.of(CLINIC_A, CLINIC_B)) {
                    setActiveClinic(connection, clinic.toString());
                    assertThat(strings(connection, "SELECT migrated::text FROM migration_probe"))
                            .containsExactly("true");
                }
                assertThat(strings(
                                connection,
                                "SELECT relforcerowsecurity::text FROM pg_class WHERE oid = 'clinic'::regclass"))
                        .containsExactly("true");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void applicationCannotDeleteOrTruncate() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setActiveClinic(connection, CLINIC_A.toString());

            assertInsufficientPrivilege(connection, "DELETE FROM clinic");
            assertInsufficientPrivilege(connection, "TRUNCATE clinic");
        }
    }

    @Test
    void applicationCannotAccessMigrationHistory() throws SQLException {
        String table = flyway.getConfiguration().getTable();
        try (Connection connection = connectAsApplication()) {
            assertInsufficientPrivilege(connection, "SELECT count(*) FROM " + table);
            assertInsufficientPrivilege(connection, "DELETE FROM " + table);
        }
    }

    @Test
    void applicationCannotCallThePolicyFunction() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            assertInsufficientPrivilege(connection, "SELECT enable_tenant_isolation('clinic', 'id')");
            assertInsufficientPrivilege(connection, "SELECT enable_user_isolation('user_account', 'id')");
        }
    }

    @Test
    void newFunctionsAreNotExecutableByTheApplication() throws SQLException {
        try (Connection owner = connectAsOwner()) {
            execute(owner, "CREATE FUNCTION probe_function() RETURNS int LANGUAGE sql AS 'SELECT 1'");
            try (Connection connection = connectAsApplication()) {
                assertInsufficientPrivilege(connection, "SELECT probe_function()");
            } finally {
                execute(owner, "DROP FUNCTION probe_function()");
            }
        }
    }

    private static void setActiveClinic(Connection connection, String clinic) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, clinic);
            statement.execute();
        }
    }

    private static void insertClinic(Connection connection, UUID id, String name) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO clinic (id, name, legal_name, tax_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, now(), now())")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setString(3, name + " S.L.");
            statement.setString(4, "B00000000");
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static int updateName(Connection connection, UUID clinic) throws SQLException {
        return update(connection, "UPDATE clinic SET name = 'Clínica Prueba Renombrada' WHERE id = ?", clinic);
    }

    private static void moveClinic(Connection connection, UUID from, UUID to) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE clinic SET id = ? WHERE id = ?")) {
            statement.setObject(1, to);
            statement.setObject(2, from);
            statement.executeUpdate();
        }
    }

    private static int update(Connection connection, String sql, UUID clinic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clinic);
            return statement.executeUpdate();
        }
    }

    private static List<String> visibleClinics(Connection connection) throws SQLException {
        return strings(connection, "SELECT id::text FROM clinic ORDER BY id");
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private static void assertInsufficientPrivilege(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(statement::execute)
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        }
    }
}
