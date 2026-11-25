package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import es.crisbr.dentasoft.TestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Test data for {@code tenancy_test_record} and the tables of {@link TenantIsolationTest}, prepared, checked and removed
 * with direct connections that do not go through the application: clinics A and B, and what each one has stored.
 *
 * <p>These connections set {@code app.tenant_id} for their whole session, which the application never does. They are
 * opened for one call and closed, never taken from the application's pool.
 */
public final class TenancyTestDatabase {

    private static final List<UUID> CLINICS = List.of(TestData.CLINIC_A.id(), TestData.CLINIC_B.id());
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

    private TenancyTestDatabase() {}

    record StoredRecord(UUID tenantId, String label) {}

    /** Creates clinics A and B as the owner: the application cannot create clinics (V003). */
    public static void insertClinics() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            insertClinic(connection, TestData.CLINIC_A);
            insertClinic(connection, TestData.CLINIC_B);
        }
    }

    static void deleteClinicsAndRecords() throws SQLException {
        deleteClinicsAnd(List.of("tenancy_test_record"));
    }

    /**
     * Deletes the rows of clinics A and B from {@code tables}, in that order, and then the clinics. The application
     * cannot delete; the owner can, one clinic at a time, as Row Level Security is forced.
     */
    public static void deleteClinicsAnd(List<String> tables) throws SQLException {
        for (UUID clinic : CLINICS) {
            deleteClinicAnd(clinic, tables);
        }
    }

    /** Deletes the rows of {@code clinic} from {@code tables}, in that order, and then the clinic. */
    public static void deleteClinicAnd(UUID clinic, List<String> tables) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            for (String table : tables) {
                execute(connection, "DELETE FROM %s WHERE tenant_id = ?".formatted(checkedTable(table)), clinic);
            }
            execute(connection, "DELETE FROM clinic WHERE id = ?", clinic);
        }
    }

    /** Creates {@code clinic} as the owner, with {@code status} ({@code ACTIVE} or {@code SUSPENDED}). */
    public static void insertClinic(TestData.Clinic clinic, String status) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            insertClinic(connection, clinic);
            try (PreparedStatement statement =
                    connection.prepareStatement("UPDATE clinic SET status = ? WHERE id = ?")) {
                statement.setString(1, status);
                statement.setObject(2, clinic.id());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Row {@code id} of {@code table} as the owner sees it with {@code clinic} active: every column, as JSON, so two
     * readings are equal only if nothing in the row changed.
     */
    public static Optional<String> storedRow(String table, UUID clinic, UUID id) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_jsonb(r)::text FROM %s r WHERE id = ?".formatted(checkedTable(table)))) {
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
                }
            }
        }
    }

    /** Number of rows of {@code table} the owner sees with {@code clinic} active. */
    public static int countRows(String table, UUID clinic) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement =
                            connection.prepareStatement("SELECT count(*) FROM %s".formatted(checkedTable(table)));
                    ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    /**
     * {@code table}, a table name, optionally with its schema, in lower case: the only shape test code may put into
     * SQL, where a name cannot be a bind parameter.
     */
    static String checkedTable(String table) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("not a table name: " + table);
        }
        return table;
    }

    /** The row as the owner sees it with {@code clinic} active. */
    static Optional<StoredRecord> stored(UUID clinic, UUID id) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT tenant_id, label FROM tenancy_test_record WHERE id = ?")) {
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            ? Optional.of(new StoredRecord(row.getObject(1, UUID.class), row.getString(2)))
                            : Optional.empty();
                }
            }
        }
    }

    static int countStored(UUID clinic) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM tenancy_test_record");
                    ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void insertClinic(Connection connection, TestData.Clinic clinic) throws SQLException {
        setSessionClinic(connection, clinic.id());
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO clinic (id, name, legal_name, tax_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'B00000000', now(), now())")) {
            statement.setObject(1, clinic.id());
            statement.setString(2, clinic.name());
            statement.setString(3, clinic.name() + " S.L.");
            statement.executeUpdate();
        }
    }

    private static void setSessionClinic(Connection connection, UUID clinic) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, clinic.toString());
            statement.execute();
        }
    }

    private static void execute(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }
}
