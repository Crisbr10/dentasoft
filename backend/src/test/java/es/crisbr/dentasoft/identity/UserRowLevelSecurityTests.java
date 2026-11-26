package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Row Level Security of users and memberships (V007), seen through SQL on the application's role, which Hibernate does
 * not filter: whatever these queries return is what PostgreSQL lets through. Each transaction sets {@code app.user_id}
 * and {@code app.tenant_id} as the application does ({@code set_config(…, true)}).
 */
@IntegrationTest
class UserRowLevelSecurityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    private static final UUID USER_A = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000a1");
    private static final UUID USER_B = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000b2");
    private static final UUID USER_C = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000c3");

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        for (UUID user : List.of(USER_A, USER_B, USER_C)) {
            IdentityTestDatabase.insertUser(user);
        }
        IdentityTestDatabase.insertMembership(CLINIC_A, USER_A, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, USER_A, ClinicRole.RECEPCION, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, USER_B, ClinicRole.ADMIN_CLINICA, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(List.of(USER_A, USER_B, USER_C));
    }

    @Test
    void withoutAClinicAUserSeesOnlyTheirOwnMemberships() throws SQLException {
        assertThat(memberships(USER_A, null))
                .containsExactlyInAnyOrder(CLINIC_A + "/" + USER_A, CLINIC_B + "/" + USER_A);
        assertThat(memberships(USER_B, null)).containsExactly(CLINIC_B + "/" + USER_B);
        assertThat(memberships(USER_C, null)).isEmpty();
    }

    /** The active clinic adds its own memberships, for managing its users (M02-T05), and nothing else. */
    @Test
    void withAnActiveClinicAUserAlsoSeesThatClinicsMemberships() throws SQLException {
        assertThat(memberships(USER_A, CLINIC_B))
                .containsExactlyInAnyOrder(CLINIC_A + "/" + USER_A, CLINIC_B + "/" + USER_A, CLINIC_B + "/" + USER_B);
        assertThat(memberships(USER_B, CLINIC_B))
                .containsExactlyInAnyOrder(CLINIC_B + "/" + USER_A, CLINIC_B + "/" + USER_B);
    }

    @Test
    void withoutAUserNoAccountMembershipOrClinicIsVisible() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            connection.setAutoCommit(false);
            assertThat(strings(connection, "SELECT id::text FROM user_account")).isEmpty();
            assertThat(strings(connection, "SELECT id::text FROM clinic_membership"))
                    .isEmpty();
            assertThat(strings(connection, "SELECT id::text FROM clinic")).isEmpty();
            connection.rollback();
        }
    }

    @Test
    void aUserOnlySeesTheirOwnAccount() throws SQLException {
        List<String> accounts =
                inTransaction(USER_A, null, connection -> strings(connection, "SELECT id::text FROM user_account"));

        assertThat(accounts).containsExactly(USER_A.toString());
    }

    @Test
    void noOneCanAddThemselvesToAClinic() throws SQLException {
        String insert = "INSERT INTO clinic_membership (id, tenant_id, user_id, role, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), '" + CLINIC_A + "', '" + USER_C + "', 'ADMIN_CLINICA', now(), now())";

        assertRejected(USER_C, null, insert);
        assertRejected(USER_C, CLINIC_B, insert);
        assertThat(memberships(USER_C, null)).isEmpty();
    }

    /** Their own membership in another clinic is visible, but neither its role nor whether it is active can change. */
    @Test
    void noOneCanChangeTheirMembershipInAnotherClinic() throws SQLException {
        IdentityTestDatabase.setMembershipActive(CLINIC_A, USER_A, false);
        String update = "UPDATE clinic_membership SET role = 'ADMIN_CLINICA', active = true" + " WHERE tenant_id = '"
                + CLINIC_A + "' AND user_id = '" + USER_A + "'";

        int withoutClinic = inTransaction(USER_A, null, connection -> update(connection, update));
        int fromClinicB = inTransaction(USER_A, CLINIC_B, connection -> update(connection, update));

        assertThat(withoutClinic).isZero();
        assertThat(fromClinicB).isZero();
        assertThat(ownerRead(
                        CLINIC_A,
                        "SELECT role || '/' || active FROM clinic_membership WHERE user_id = '" + USER_A + "'"))
                .containsExactly("ODONTOLOGO/false");
    }

    /**
     * In the active clinic a membership's role and status may change, but never whose or which clinic's it is: no
     * privilege on {@code user_id}, and Row Level Security on {@code tenant_id}.
     */
    @Test
    void inTheActiveClinicAMembershipCannotBeMovedToAnotherUserOrClinic() throws SQLException {
        String where = " WHERE tenant_id = '" + CLINIC_B + "' AND user_id = '" + USER_B + "'";

        assertRejected(USER_A, CLINIC_B, "UPDATE clinic_membership SET user_id = '" + USER_A + "'" + where);
        assertRejected(USER_A, CLINIC_B, "UPDATE clinic_membership SET tenant_id = '" + CLINIC_A + "'" + where);
        int roleChanged = inTransaction(
                USER_A,
                CLINIC_B,
                connection -> update(connection, "UPDATE clinic_membership SET role = 'RECEPCION'" + where));

        assertThat(roleChanged).isEqualTo(1);
    }

    @Test
    void theApplicationCannotCreateAnotherUsersAccountNorChangeAnyAccount() throws SQLException {
        UUID other = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000d4");

        assertRejected(
                USER_A,
                null,
                "INSERT INTO user_account (id, status, created_at, updated_at)" + " VALUES ('" + other
                        + "', 'ACTIVE', now(), now())");
        assertRejected(USER_A, null, "UPDATE user_account SET status = 'DISABLED' WHERE id = '" + USER_A + "'");
        assertThat(IdentityTestDatabase.userStatus(other)).isEmpty();
        assertThat(IdentityTestDatabase.userStatus(USER_A)).contains("ACTIVE");
    }

    /** Inviting someone (M02-T05, V008): inside the active clinic, the account of another user can be created. */
    @Test
    void inAnActiveClinicTheApplicationCanCreateTheActiveAccountOfAnInvitedUser() throws SQLException {
        UUID invited = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000e5");
        try {
            int created = inTransactionCommitted(
                    USER_B, CLINIC_B, connection -> update(connection, invite(invited, "ACTIVE", USER_B)));

            assertThat(created).isEqualTo(1);
            assertThat(IdentityTestDatabase.userStatus(invited)).contains("ACTIVE");
            // Created, but as invisible to the inviting user as any other account.
            List<String> visible = inTransaction(
                    USER_B, CLINIC_B, connection -> strings(connection, "SELECT id::text FROM user_account"));
            assertThat(visible).containsExactly(USER_B.toString());
        } finally {
            IdentityTestDatabase.deleteUsers(List.of(invited));
        }
    }

    @Test
    void anInvitedUsersAccountIsOnlyCreatedActiveByAnAdministratorOfTheClinicInTheirName() throws SQLException {
        UUID invited = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000e6");
        String updatedByAnother =
                invite(invited, "ACTIVE", USER_B).replace("now(), '" + USER_B + "')", "now(), '" + USER_A + "')");

        assertRejected(USER_B, CLINIC_B, invite(invited, "DISABLED", USER_B));
        assertRejected(USER_B, null, invite(invited, "ACTIVE", USER_B));
        assertRejected(USER_B, CLINIC_B, invite(invited, "ACTIVE", USER_A));
        assertRejected(USER_B, CLINIC_B, updatedByAnother);
        // USER_A is a receptionist in clinic B, and USER_C belongs to no clinic: neither is an administrator there.
        assertRejected(USER_A, CLINIC_B, invite(invited, "ACTIVE", USER_A));
        assertRejected(USER_C, CLINIC_B, invite(invited, "ACTIVE", USER_C));
        // An administrator of clinic B acting as clinic A, where they are not one.
        assertRejected(USER_B, CLINIC_A, invite(invited, "ACTIVE", USER_B));
        IdentityTestDatabase.setMembershipActive(CLINIC_B, USER_B, false);
        assertRejected(USER_B, CLINIC_B, invite(invited, "ACTIVE", USER_B));
        assertThat(IdentityTestDatabase.userStatus(invited)).isEmpty();
    }

    /**
     * Inviting someone who already has an account, the usual case for a person working in another clinic: their
     * account is not visible, so {@code ON CONFLICT DO NOTHING} is refused, and V008's function is what the application
     * uses. The account stays as it is, even a disabled one, and nothing else is allowed on it.
     */
    @Test
    void invitingAUserWhoAlreadyHasAnAccountLeavesItUnchanged() throws SQLException {
        UUID disabled = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000f7");
        IdentityTestDatabase.insertUser(disabled, "DISABLED");
        try {
            String createInvited = "SELECT create_invited_user_account('" + disabled + "')";
            List<String> result = inTransaction(USER_B, CLINIC_B, connection -> strings(connection, createInvited));

            assertThat(result).hasSize(1);
            assertRejected(USER_B, CLINIC_B, invite(disabled, "ACTIVE", USER_B) + " ON CONFLICT (id) DO NOTHING");
            assertRejected(USER_B, CLINIC_B, "UPDATE user_account SET status = 'ACTIVE' WHERE id = '" + disabled + "'");
            assertRejected(USER_B, CLINIC_B, "DELETE FROM user_account WHERE id = '" + disabled + "'");
            assertThat(IdentityTestDatabase.userStatus(disabled)).contains("DISABLED");
        } finally {
            IdentityTestDatabase.deleteUsers(List.of(disabled));
        }
    }

    /** The function creates the account of someone new, in the name of the requesting user, and only in a clinic. */
    @Test
    void theInvitationFunctionCreatesNewAccountsOnlyInsideAClinic() throws SQLException {
        UUID invited = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000000e8");
        String createInvited = "SELECT create_invited_user_account('" + invited + "')";
        try {
            assertRejected(USER_B, null, createInvited);
            assertThat(IdentityTestDatabase.userStatus(invited)).isEmpty();

            inTransactionCommitted(USER_B, CLINIC_B, connection -> strings(connection, createInvited));

            assertThat(IdentityTestDatabase.userStatus(invited)).contains("ACTIVE");
            assertThat(IdentityTestDatabase.userCreatedBy(invited)).contains(USER_B);
        } finally {
            IdentityTestDatabase.deleteUsers(List.of(invited));
        }
    }

    @Test
    void withoutAClinicAUserSeesOnlyTheClinicsOfTheirActiveMemberships() throws SQLException {
        assertThat(clinics(USER_A)).containsExactlyInAnyOrder(CLINIC_A.toString(), CLINIC_B.toString());
        assertThat(clinics(USER_B)).containsExactly(CLINIC_B.toString());
        assertThat(clinics(USER_C)).isEmpty();
    }

    @Test
    void aDeactivatedMembershipNoLongerShowsItsClinic() throws SQLException {
        IdentityTestDatabase.setMembershipActive(CLINIC_B, USER_A, false);

        assertThat(clinics(USER_A)).containsExactly(CLINIC_A.toString());
        assertThat(clinics(USER_B)).containsExactly(CLINIC_B.toString());
    }

    /** The policies look memberships up by user on every read, without a clinic (V007). */
    @Test
    void membershipsAreIndexedByUser() throws SQLException {
        assertThat(ownerRead(
                        CLINIC_A,
                        "SELECT a.attname FROM pg_index i"
                                + " JOIN pg_class c ON c.oid = i.indrelid AND c.relname = 'clinic_membership'"
                                + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]"))
                .contains("tenant_id", "user_id");
    }

    private List<String> memberships(UUID user, @Nullable UUID clinic) throws SQLException {
        return inTransaction(
                user,
                clinic,
                connection ->
                        strings(connection, "SELECT tenant_id::text || '/' || user_id::text FROM clinic_membership"));
    }

    private List<String> clinics(UUID user) throws SQLException {
        return inTransaction(user, null, connection -> strings(connection, "SELECT id::text FROM clinic"));
    }

    private void assertRejected(UUID user, @Nullable UUID clinic, String sql) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> inTransaction(user, clinic, connection -> update(connection, sql)))
                .extracting(SQLException::getSQLState)
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    /** The insert of an invited user's account, as the application makes it, by {@code creator}. */
    private static String invite(UUID user, String status, UUID creator) {
        return "INSERT INTO user_account (id, status, created_at, created_by, updated_at, updated_by)" + " VALUES ('"
                + user + "', '" + status + "', now(), '" + creator + "', now(), '" + creator + "')";
    }

    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One transaction on the application's role, with the user and clinic set as the application sets them. */
    private static <T> T inTransaction(UUID user, @Nullable UUID clinic, Work<T> work) throws SQLException {
        try (Connection connection = connectAsApplication()) {
            connection.setAutoCommit(false);
            try {
                setLocal(connection, "app.user_id", user);
                if (clinic != null) {
                    setLocal(connection, "app.tenant_id", clinic);
                }
                return work.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Like {@link #inTransaction}, but committed, for checking the result as the owner afterwards. */
    private static <T> T inTransactionCommitted(UUID user, UUID clinic, Work<T> work) throws SQLException {
        try (Connection connection = connectAsApplication()) {
            connection.setAutoCommit(false);
            setLocal(connection, "app.user_id", user);
            setLocal(connection, "app.tenant_id", clinic);
            T result = work.run(connection);
            connection.commit();
            return result;
        }
    }

    private static List<String> ownerRead(UUID clinic, String sql) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            connection.setAutoCommit(false);
            try {
                setLocal(connection, "app.tenant_id", clinic);
                return strings(connection, sql);
            } finally {
                connection.rollback();
            }
        }
    }

    /** {@code setting} is a fixed name from this class. */
    private static void setLocal(Connection connection, String setting, UUID value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            statement.setString(1, setting);
            statement.setString(2, value.toString());
            statement.execute();
        }
    }

    /** SQL with fixed identifiers and test ids from this class: nothing here comes from outside the test. */
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

    private static int update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }
}
