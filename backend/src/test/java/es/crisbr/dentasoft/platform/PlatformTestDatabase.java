package es.crisbr.dentasoft.platform;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and deletes, as the owner, the clinics the platform tests create. Row Level Security is forced on the owner
 * too, so each clinic is reached with itself as the session's clinic.
 */
public final class PlatformTestDatabase {

    private PlatformTestDatabase() {}

    /** The clinic's status, if the clinic exists. */
    public static Optional<String> status(UUID clinic) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM clinic WHERE id = ?")) {
                statement.setObject(1, clinic);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        }
    }

    /** The default appointment length in the clinic's settings, if the clinic has settings. */
    public static Optional<Integer> defaultAppointmentMinutes(UUID clinic) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT default_appointment_minutes FROM clinic_settings WHERE tenant_id = ?")) {
                statement.setObject(1, clinic);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getInt(1)) : Optional.empty();
                }
            }
        }
    }

    public static void deleteClinics(Collection<UUID> clinics) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            for (UUID clinic : clinics) {
                setSessionClinic(connection, clinic);
                execute(connection, "DELETE FROM clinic_settings WHERE tenant_id = ?", clinic);
                execute(connection, "DELETE FROM clinic WHERE id = ?", clinic);
            }
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
