package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import es.crisbr.dentasoft.TestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test data for {@code tenancy_test_record}, prepared, checked and removed with direct connections that do not go
 * through the application: clinics A and B, and what each one has stored.
 *
 * <p>These connections set {@code app.tenant_id} for their whole session, which the application never does. They are
 * opened for one call and closed, never taken from the application's pool.
 */
final class TenancyTestDatabase {

    private static final List<UUID> CLINICS = List.of(TestData.CLINIC_A.id(), TestData.CLINIC_B.id());

    private TenancyTestDatabase() {}

    record StoredRecord(UUID tenantId, String label) {}

    /** Creates clinics A and B; there is no application service for it yet (M01-T05). */
    static void insertClinics() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            insertClinic(connection, TestData.CLINIC_A);
            insertClinic(connection, TestData.CLINIC_B);
        }
    }

    /** The application cannot delete; the owner can, one clinic at a time, as Row Level Security is forced. */
    static void deleteClinicsAndRecords() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            for (UUID clinic : CLINICS) {
                setSessionClinic(connection, clinic);
                execute(connection, "DELETE FROM tenancy_test_record WHERE tenant_id = ?", clinic);
                execute(connection, "DELETE FROM clinic WHERE id = ?", clinic);
            }
        }
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
