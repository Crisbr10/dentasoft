package es.crisbr.dentasoft.audit;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static es.crisbr.dentasoft.audit.AuditLogDatabase.setSessionClinic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code audit_log} at the database (V004): the application may only insert, under its active clinic; nobody, not
 * even the owner, may change or delete an entry; and the platform may only write its own entries.
 *
 * <p>Not a {@code TenantIsolationTest}: that template stores and changes rows through JPA and checks that another
 * clinic cannot update or delete them, and here nobody can read, update or delete at all. These tests cover the same
 * ground for an insert-only table. No clinic rows are needed: the log has no foreign key to {@code clinic}.
 */
@IntegrationTest
class AuditLogDatabaseTests {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String CHECK_VIOLATION = "23514";

    private final UUID clinicA = UUID.randomUUID();
    private final UUID clinicB = UUID.randomUUID();

    @Test
    void theApplicationWritesUnderItsActiveClinic() throws SQLException {
        UUID entity = UUID.randomUUID();
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            insert(connection, null, "SYSTEM", entity);
        }

        assertThat(AuditLogDatabase.entries(clinicA, entity))
                .singleElement()
                .satisfies(entry -> assertThat(entry.tenantId()).isEqualTo(clinicA));
        assertThat(AuditLogDatabase.entries(clinicB, entity)).isEmpty();
    }

    @Test
    void theApplicationCannotWriteUnderAnotherClinic() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            assertFails(INSUFFICIENT_PRIVILEGE, () -> insert(connection, clinicB, "SYSTEM", UUID.randomUUID()));
        }
    }

    /**
     * {@code tenant_id} defaults to NULL, which Row Level Security rejects before {@code NOT NULL} does: either way, an
     * entry without a clinic is never stored.
     */
    @Test
    void withoutAnActiveClinicNothingIsWritten() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            assertFails(INSUFFICIENT_PRIVILEGE, () -> insert(connection, null, "SYSTEM", UUID.randomUUID()));
        }
    }

    @Test
    void theApplicationCannotReadChangeOrDeleteEntries() throws SQLException {
        UUID entity = UUID.randomUUID();
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            insert(connection, null, "SYSTEM", entity);

            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "SELECT count(*) FROM audit_log");
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "UPDATE audit_log SET outcome = 'DENIED'");
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "DELETE FROM audit_log");
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "TRUNCATE audit_log");
        }
        assertThat(AuditLogDatabase.entries(clinicA, entity)).singleElement();
    }

    /** The trigger stops the owner too, who has every privilege: only a migration that drops it could. */
    @Test
    void notEvenTheOwnerCanChangeOrDeleteEntries() throws SQLException {
        UUID entity = UUID.randomUUID();
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinicA);
            insert(connection, null, "SYSTEM", entity);

            assertAppendOnly(connection, "UPDATE audit_log SET outcome = 'DENIED' WHERE entity_id = '" + entity + "'");
            assertAppendOnly(connection, "DELETE FROM audit_log WHERE entity_id = '" + entity + "'");
            assertAppendOnly(connection, "TRUNCATE audit_log");
        }
        assertThat(AuditLogDatabase.entries(clinicA, entity))
                .singleElement()
                .satisfies(entry -> assertThat(entry.outcome()).isEqualTo("SUCCESS"));
    }

    @Test
    void thePlatformWritesItsOwnEntriesUnderTheAffectedClinic() throws SQLException {
        UUID clinic = UUID.randomUUID();
        try (Connection connection = connectAsPlatform()) {
            insert(connection, clinic, "PLATFORM", clinic);
        }

        assertThat(AuditLogDatabase.entries(clinic, clinic))
                .singleElement()
                .satisfies(entry -> assertThat(entry.actorType()).isEqualTo("PLATFORM"));
    }

    @Test
    void thePlatformCannotWriteAsAnotherActorNorReadTheLog() throws SQLException {
        try (Connection connection = connectAsPlatform()) {
            assertFails(INSUFFICIENT_PRIVILEGE, () -> insert(connection, clinicA, "SYSTEM", clinicA));
            assertFails(INSUFFICIENT_PRIVILEGE, () -> insert(connection, clinicA, "USER", clinicA));
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "SELECT count(*) FROM audit_log");
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "DELETE FROM audit_log");
        }
    }

    /** Codes outside their closed lists, and a user without an id, never reach the table. */
    @Test
    void onlyClosedCodesAreStored() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            assertFails(CHECK_VIOLATION, () -> insert(connection, null, "Juan", UUID.randomUUID()));
            assertFails(CHECK_VIOLATION, () -> insert(connection, null, "USER", UUID.randomUUID()));
            assertRejected(
                    connection,
                    CHECK_VIOLATION,
                    "INSERT INTO audit_log (id, occurred_at, actor_type, action, entity_type, entity_id, outcome)"
                            + " VALUES (gen_random_uuid(), now(), 'SYSTEM', 'READ', 'Paciente Juan', gen_random_uuid(),"
                            + " 'SUCCESS')");
            assertRejected(
                    connection,
                    CHECK_VIOLATION,
                    "INSERT INTO audit_log (id, occurred_at, actor_type, action, entity_type, entity_id, outcome,"
                            + " details) VALUES (gen_random_uuid(), now(), 'SYSTEM', 'READ', 'CLINIC',"
                            + " gen_random_uuid(), 'SUCCESS', '\"texto libre\"')");
        }
    }

    private static void insert(Connection connection, @Nullable UUID tenantId, String actorType, UUID entityId)
            throws SQLException {
        String columns = tenantId == null ? "" : "tenant_id, ";
        String values = tenantId == null ? "" : "?, ";
        try (PreparedStatement statement = connection.prepareStatement(("INSERT INTO audit_log (id, %soccurred_at,"
                        + " actor_type, action, entity_type, entity_id, outcome) VALUES (gen_random_uuid(), %snow(), ?,"
                        + " 'READ', 'CLINIC', ?, 'SUCCESS')")
                .formatted(columns, values))) {
            int index = 1;
            if (tenantId != null) {
                statement.setObject(index++, tenantId);
            }
            statement.setString(index++, actorType);
            statement.setObject(index, entityId);
            statement.executeUpdate();
        }
    }

    private static void assertRejected(Connection connection, String sqlState, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            assertFails(sqlState, statement::execute);
        }
    }

    private static void assertAppendOnly(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(statement::execute)
                    .withMessageContaining("audit_log is append-only");
        }
    }

    private static void assertFails(String sqlState, SqlCall call) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(call::run)
                .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(sqlState));
    }

    @FunctionalInterface
    private interface SqlCall {
        void run() throws SQLException;
    }
}
