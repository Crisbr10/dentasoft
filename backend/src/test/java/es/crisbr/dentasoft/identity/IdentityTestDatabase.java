package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Test data for {@code user_account} and {@code clinic_membership} (V007), prepared, read and removed as the owner with
 * direct connections that do not go through the application. Row Level Security is forced on both tables, also for
 * the owner, so each call sets the user or the clinic for its own session, which the application never does.
 *
 * <p>Delete memberships first ({@code TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"))}) and users
 * after: memberships point at their user.
 */
public final class IdentityTestDatabase {

    private IdentityTestDatabase() {}

    /** Creates the account of {@code user}, if it does not exist, with {@code status} ACTIVE or DISABLED. */
    public static void insertUser(UUID user, String status) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.user_id", user);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO user_account (id, status, created_at, updated_at) VALUES (?, ?, now(), now())"
                            + " ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status")) {
                statement.setObject(1, user);
                statement.setString(2, status);
                statement.executeUpdate();
            }
        }
    }

    public static void insertUser(UUID user) throws SQLException {
        insertUser(user, "ACTIVE");
    }

    /** The status of {@code user}'s account, empty when it does not exist. */
    public static Optional<String> userStatus(UUID user) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.user_id", user);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT status FROM user_account WHERE id = ?")) {
                statement.setObject(1, user);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        }
    }

    /** Who created {@code user}'s account, empty when it does not exist or has no creator. */
    public static Optional<UUID> userCreatedBy(UUID user) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.user_id", user);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT created_by FROM user_account WHERE id = ?")) {
                statement.setObject(1, user);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.ofNullable(result.getObject(1, UUID.class)) : Optional.empty();
                }
            }
        }
    }

    /** How many accounts {@code user} has: 0 or 1. */
    public static int userCount(UUID user) throws SQLException {
        return userStatus(user).isPresent() ? 1 : 0;
    }

    /** Deletes the accounts of {@code users}, after their memberships. */
    public static void deleteUsers(Collection<UUID> users) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            for (UUID user : users) {
                setSession(connection, "app.user_id", user);
                try (PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM user_account WHERE id = ?")) {
                    statement.setObject(1, user);
                    statement.executeUpdate();
                }
            }
        }
    }

    /** Gives {@code user} the {@code role} in {@code clinic}, with a fresh id, as an accepted membership. */
    public static UUID insertMembership(UUID clinic, UUID user, ClinicRole role, boolean active) throws SQLException {
        return insertMembership(clinic, user, role, active, true);
    }

    /** Invites {@code user} to {@code clinic} with {@code role}: an active membership they have not accepted yet. */
    public static UUID insertInvitation(UUID clinic, UUID user, ClinicRole role) throws SQLException {
        return insertMembership(clinic, user, role, true, false);
    }

    private static UUID insertMembership(UUID clinic, UUID user, ClinicRole role, boolean active, boolean accepted)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.tenant_id", clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO clinic_membership (id, tenant_id, user_id, role, active, created_at, updated_at,"
                            + " accepted_at) VALUES (?, ?, ?, ?, ?, now(), now(), " + (accepted ? "now()" : "NULL")
                            + ")")) {
                statement.setObject(1, id);
                statement.setObject(2, clinic);
                statement.setObject(3, user);
                statement.setString(4, role.name());
                statement.setBoolean(5, active);
                statement.executeUpdate();
            }
        }
        return id;
    }

    /** {@code user}'s membership of {@code clinic} as {@code ROLE/active}, empty if there is none. */
    public static Optional<String> membership(UUID clinic, UUID user) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.tenant_id", clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT role || '/' || active FROM clinic_membership WHERE tenant_id = ? AND user_id = ?")) {
                statement.setObject(1, clinic);
                statement.setObject(2, user);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        }
    }

    /** Makes {@code user}'s membership of {@code clinic} active or inactive. */
    public static void setMembershipActive(UUID clinic, UUID user, boolean active) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSession(connection, "app.tenant_id", clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE clinic_membership SET active = ? WHERE tenant_id = ? AND user_id = ?")) {
                statement.setBoolean(1, active);
                statement.setObject(2, clinic);
                statement.setObject(3, user);
                statement.executeUpdate();
            }
        }
    }

    /** {@code setting} is {@code app.user_id} or {@code app.tenant_id}: never a value from outside the test. */
    private static void setSession(Connection connection, String setting, UUID value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, setting);
            statement.setString(2, value.toString());
            statement.execute();
        }
    }
}
