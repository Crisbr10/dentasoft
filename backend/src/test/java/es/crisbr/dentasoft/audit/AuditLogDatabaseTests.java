package es.crisbr.dentasoft.audit;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static es.crisbr.dentasoft.audit.AuditLogDatabase.setSessionClinic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code audit_log} at the database (V004): the application may only insert, under its active clinic, and neither
 * the clinic, the time nor the details are its to choose; nobody, not even the owner, may change or delete an entry;
 * and the platform may only record what it does to a clinic.
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
    void theApplicationWritesUnderItsActiveClinicAtTheDatabaseTime() throws SQLException {
        UUID entity = UUID.randomUUID();
        Instant before = Instant.now();
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            insert(connection, "SYSTEM", "READ", "CLINIC", entity);
        }

        assertThat(AuditLogDatabase.entries(clinicA, entity))
                .singleElement()
                .satisfies(entry -> assertThat(entry.tenantId()).isEqualTo(clinicA));
        assertThat(AuditLogDatabase.entries(clinicB, entity)).isEmpty();
        assertThat(occurredAt(clinicA, entity))
                .isBetween(before.minus(Duration.ofMinutes(1)), Instant.now().plus(Duration.ofMinutes(1)));
    }

    /** Whoever inserts chooses neither the clinic nor the time, and the application never writes details. */
    @Test
    void theApplicationCannotChooseTheClinicTheTimeOrTheDetails() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, entryWith("tenant_id", "'" + clinicB + "'"));
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, entryWith("tenant_id", "'" + clinicA + "'"));
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, entryWith("occurred_at", "'2020-01-01T00:00:00Z'"));
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, entryWith("details", "'{\"nota\": \"Juan Pérez\"}'"));
        }
    }

    @Test
    void theApplicationMayInsertOnlyTheColumnsItFills() throws SQLException {
        try (Connection connection = connectAsOwner();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT attname FROM pg_attribute WHERE attrelid = 'audit_log'::regclass AND attnum > 0"
                                + " AND NOT attisdropped AND has_column_privilege(?, 'audit_log', attname, 'INSERT')"
                                + " ORDER BY attnum")) {
            statement.setString(1, APP_USER);
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
            assertThat(columns).containsExactlyElementsOf(AuditLogDatabase.APPLICATION_INSERT_COLUMNS);
        }
    }

    /**
     * {@code tenant_id} defaults to NULL, which Row Level Security rejects before {@code NOT NULL} does: either way, an
     * entry without a clinic is never stored.
     */
    @Test
    void withoutAnActiveClinicNothingIsWritten() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            assertFails(
                    INSUFFICIENT_PRIVILEGE, () -> insert(connection, "SYSTEM", "READ", "CLINIC", UUID.randomUUID()));
        }
    }

    @Test
    void theApplicationCannotReadChangeOrDeleteEntries() throws SQLException {
        UUID entity = UUID.randomUUID();
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            insert(connection, "SYSTEM", "READ", "CLINIC", entity);

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
            insert(connection, "SYSTEM", "READ", "CLINIC", entity);

            assertAppendOnly(connection, "UPDATE audit_log SET outcome = 'DENIED' WHERE entity_id = '" + entity + "'");
            assertAppendOnly(connection, "DELETE FROM audit_log WHERE entity_id = '" + entity + "'");
            assertAppendOnly(connection, "TRUNCATE audit_log");
        }
        assertThat(AuditLogDatabase.entries(clinicA, entity))
                .singleElement()
                .satisfies(entry -> assertThat(entry.outcome()).isEqualTo("SUCCESS"));
    }

    @Test
    void thePlatformRecordsWhatItDoesToAClinicUnderThatClinic() throws SQLException {
        UUID clinic = UUID.randomUUID();
        try (Connection connection = connectAsPlatform()) {
            insertForClinic(connection, clinic, "PLATFORM", "CREATE", "CLINIC", clinic);
            insertForClinic(connection, clinic, "PLATFORM", "UPDATE", "CLINIC", clinic);
        }

        assertThat(AuditLogDatabase.entries(clinic, clinic))
                .extracting(AuditLogDatabase.StoredEntry::actorType, AuditLogDatabase.StoredEntry::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PLATFORM", "CREATE"),
                        org.assertj.core.groups.Tuple.tuple("PLATFORM", "UPDATE"));
    }

    /** Only entries of its own, about the clinic they are stored under, creating or changing it. */
    @Test
    void thePlatformCannotRecordAnythingElse() throws SQLException {
        UUID other = UUID.randomUUID();
        try (Connection connection = connectAsPlatform()) {
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "SYSTEM", "UPDATE", "CLINIC", clinicA));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "USER", "UPDATE", "CLINIC", clinicA));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "PLATFORM", "READ", "CLINIC", clinicA));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "PLATFORM", "DELETE", "CLINIC", clinicA));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "PLATFORM", "UPDATE", "LOCATION", clinicA));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> insertForClinic(connection, clinicA, "PLATFORM", "UPDATE", "CLINIC", other));
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "SELECT count(*) FROM audit_log");
            assertRejected(connection, INSUFFICIENT_PRIVILEGE, "DELETE FROM audit_log");
        }
    }

    /** Codes outside their closed lists, and a user without an id, never reach the table. */
    @Test
    void onlyClosedCodesAreStored() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            setSessionClinic(connection, clinicA);
            assertFails(CHECK_VIOLATION, () -> insert(connection, "Juan", "READ", "CLINIC", UUID.randomUUID()));
            assertFails(CHECK_VIOLATION, () -> insert(connection, "USER", "READ", "CLINIC", UUID.randomUUID()));
            assertFails(CHECK_VIOLATION, () -> insert(connection, "SYSTEM", "LEER", "CLINIC", UUID.randomUUID()));
            assertFails(
                    CHECK_VIOLATION, () -> insert(connection, "SYSTEM", "READ", "Paciente Juan", UUID.randomUUID()));
        }
        try (Connection connection = connectAsPlatform()) {
            assertFails(CHECK_VIOLATION, () -> insertWithDetails(connection, clinicA, "\"texto libre\""));
        }
    }

    /** An entry as the application writes it: the database fills in the clinic and the time. */
    private static void insert(Connection connection, String actorType, String action, String entityType, UUID entityId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_log (id, actor_type, action, entity_type, entity_id, outcome)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 'SUCCESS')")) {
            statement.setString(1, actorType);
            statement.setString(2, action);
            statement.setString(3, entityType);
            statement.setObject(4, entityId);
            statement.executeUpdate();
        }
    }

    /** An entry as the platform writes it: it names the clinic. */
    private static void insertForClinic(
            Connection connection, UUID tenantId, String actorType, String action, String entityType, UUID entityId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, entity_id, outcome)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'SUCCESS')")) {
            statement.setObject(1, tenantId);
            statement.setString(2, actorType);
            statement.setString(3, action);
            statement.setString(4, entityType);
            statement.setObject(5, entityId);
            statement.executeUpdate();
        }
    }

    private static void insertWithDetails(Connection connection, UUID clinic, String details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, entity_id, outcome, details)"
                        + " VALUES (gen_random_uuid(), ?, 'PLATFORM', 'UPDATE', 'CLINIC', ?, 'SUCCESS', ?::jsonb)")) {
            statement.setObject(1, clinic);
            statement.setObject(2, clinic);
            statement.setString(3, details);
            statement.executeUpdate();
        }
    }

    /** An application entry that also names {@code column}, which the database should fill in itself. */
    private static String entryWith(String column, @Nullable String value) {
        return ("INSERT INTO audit_log (id, actor_type, action, entity_type, entity_id, outcome, %s)"
                        + " VALUES (gen_random_uuid(), 'SYSTEM', 'READ', 'CLINIC', gen_random_uuid(), 'SUCCESS', %s)")
                .formatted(column, value);
    }

    private static Instant occurredAt(UUID clinic, UUID entity) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT occurred_at FROM audit_log WHERE entity_id = ?")) {
                statement.setObject(1, entity);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return row.getTimestamp(1).toInstant();
                }
            }
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
