package es.crisbr.dentasoft.platform;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

/**
 * What PostgreSQL lets the platform do with the first administrator of a clinic (V013, ADR 0018), through SQL on the
 * platform's role, which nothing in the application filters. Each transaction sets {@code app.user_id} as the
 * application does ({@code set_config(…, true)}), and never {@code app.tenant_id}: the platform has no active clinic.
 */
@IntegrationTest
class PlatformFirstAdminDatabaseTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    private static final UUID PLATFORM_ADMIN = UUID.fromString("7a1d0c00-0000-4000-8000-0000000000f1");
    private static final UUID ANA = UUID.fromString("7a1d0c00-0000-4000-8000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("7a1d0c00-0000-4000-8000-0000000000b2");
    private static final UUID CARLA = UUID.fromString("7a1d0c00-0000-4000-8000-0000000000c3");
    private static final List<UUID> USERS = List.of(ANA, BRUNO, CARLA, PLATFORM_ADMIN);

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final int TIMEOUT_SECONDS = 30;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(USERS);
    }

    @Test
    void invitesTheFirstAdministratorPendingAndInItsOwnName() throws SQLException {
        UUID invitation = invite(CLINIC_A, ANA);

        assertThat(IdentityTestDatabase.userStatus(ANA)).contains("ACTIVE");
        assertThat(IdentityTestDatabase.userCreatedBy(ANA)).contains(PLATFORM_ADMIN);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/true");
        assertThat(adminMemberships()).containsExactly(invitation + "/" + CLINIC_A + "/" + ANA + "/true/pending");
    }

    /** {@code create_invited_user_account} skips an account that exists, which the platform cannot even see. */
    @Test
    void invitesSomeoneWhoAlreadyHasAnAccount() throws SQLException {
        IdentityTestDatabase.insertUser(ANA);

        invite(CLINIC_A, ANA);

        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void refusesASecondInvitationWhileOneIsPending() throws SQLException {
        invite(CLINIC_A, ANA);

        assertFirstAdminRefused(() -> invite(CLINIC_A, BRUNO));
        assertThat(IdentityTestDatabase.membership(CLINIC_A, BRUNO)).isEmpty();
    }

    @Test
    void refusesAnInvitationWhenTheClinicHasAnAdministrator() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ADMIN_CLINICA, true);

        assertFirstAdminRefused(() -> invite(CLINIC_A, ANA));
    }

    /** An inactive administrator, or one of another clinic, does not count. */
    @Test
    void countsOnlyTheActiveAdministratorsOfTheSameClinic() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ADMIN_CLINICA, false);
        IdentityTestDatabase.insertMembership(CLINIC_B, CARLA, ClinicRole.ADMIN_CLINICA, true);

        invite(CLINIC_A, ANA);

        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/true");
    }

    /**
     * Two invitations at the same time: the second waits for the first's lock on the clinic's row and then sees its
     * membership, so the clinic gets one first administrator, not two.
     */
    @Test
    void twoConcurrentInvitationsCreateOneAdministrator() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = platformTransaction()) {
            createAccount(first, ANA);
            insertMembership(first, CLINIC_A, ANA, "ADMIN_CLINICA");

            Future<Throwable> second = executor.submit(() -> {
                try (Connection connection = platformTransaction()) {
                    createAccount(connection, BRUNO);
                    insertMembership(connection, CLINIC_A, BRUNO, "ADMIN_CLINICA");
                    connection.commit();
                    return null;
                } catch (SQLException refused) {
                    return refused;
                }
            });
            awaitAWaitingTransaction(second);
            first.commit();

            Throwable outcome = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(PSQLException.class);
            assertFirstAdminRefusal((PSQLException) outcome);
        } finally {
            executor.shutdownNow();
        }
        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/true");
        assertThat(IdentityTestDatabase.membership(CLINIC_A, BRUNO)).isEmpty();
    }

    @Test
    void refusesAnyRoleButAdministrator() throws SQLException {
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                createAccount(connection, ANA);
                insertMembership(connection, CLINIC_A, ANA, "ODONTOLOGO");
            }
        });
    }

    /** Without {@code app.user_id} nobody is named in {@code created_by}, and nothing is created. */
    @Test
    void refusesAnInvitationWithoutAUser() throws SQLException {
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = connectAsPlatform()) {
                connection.setAutoCommit(false);
                createAccount(connection, ANA);
            }
        });
    }

    @Test
    void cancelsAPendingInvitationAndThenAllowsAnother() throws SQLException {
        UUID invitation = invite(CLINIC_A, ANA);

        assertThat(setActive(invitation, false)).isEqualTo(1);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/false");

        invite(CLINIC_A, BRUNO);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, BRUNO)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void reactivatesACancelledInvitationOnlyWhileNoOtherIsPending() throws SQLException {
        UUID first = invite(CLINIC_A, ANA);
        setActive(first, false);
        UUID second = invite(CLINIC_A, BRUNO);

        assertFirstAdminRefused(() -> setActive(first, true));

        setActive(second, false);
        assertThat(setActive(first, true)).isEqualTo(1);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, ANA)).contains("ADMIN_CLINICA/true");
    }

    /** Accepting is the invited person's alone: the platform has no privilege on {@code accepted_at}. */
    @Test
    void cannotAcceptAnInvitation() throws SQLException {
        UUID invitation = invite(CLINIC_A, ANA);

        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                update(connection, "UPDATE clinic_membership SET accepted_at = now() WHERE id = ?", invitation);
            }
        });
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                update(connection, "UPDATE clinic_membership SET role = 'ODONTOLOGO' WHERE id = ?", invitation);
            }
        });
    }

    /**
     * Once the clinic has an accepted administrator, the pending invitations left are the clinic's own (M02-T05): the
     * trigger refuses cancelling them, although {@code platform_update} alone would let the platform.
     */
    @Test
    void cannotCancelTheClinicsOwnInvitationOnceItHasAnAdministrator() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        IdentityTestDatabase.insertUser(BRUNO);
        IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ADMIN_CLINICA, true);
        UUID coAdmin = IdentityTestDatabase.insertInvitation(CLINIC_A, BRUNO, ClinicRole.ADMIN_CLINICA);

        assertRejected(INSUFFICIENT_PRIVILEGE, () -> setActive(coAdmin, false));
        assertThat(IdentityTestDatabase.membership(CLINIC_A, BRUNO)).contains("ADMIN_CLINICA/true");
    }

    /** The platform administrator never gets a membership: they use a separate account to work in a clinic. */
    @Test
    void cannotInviteItself() throws SQLException {
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                createAccount(connection, PLATFORM_ADMIN);
                insertMembership(connection, CLINIC_A, PLATFORM_ADMIN, "ADMIN_CLINICA");
            }
        });
    }

    /**
     * With {@code app.tenant_id} set, {@code tenant_isolation} (TO PUBLIC) would let the platform reach every membership
     * of the clinic and write any audit entry there. The restrictive {@code platform_without_clinic} leaves it nothing
     * at all: it works without an active clinic, or not at all.
     */
    @Test
    void withAnActiveClinicThePlatformGetsNothing() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        IdentityTestDatabase.insertUser(BRUNO);
        UUID admin = IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ADMIN_CLINICA, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, BRUNO, ClinicRole.ODONTOLOGO, true);

        try (Connection connection = platformTransactionIn(CLINIC_A);
                PreparedStatement read =
                        connection.prepareStatement("SELECT count(*) FROM clinic_membership WHERE tenant_id = ?")) {
            read.setObject(1, CLINIC_A);
            try (ResultSet result = read.executeQuery()) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
        try (Connection connection = platformTransactionIn(CLINIC_A);
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE clinic_membership SET active = false, updated_by = ? WHERE id = ?")) {
            update.setObject(1, PLATFORM_ADMIN);
            update.setObject(2, admin);
            assertThat(update.executeUpdate()).isZero();
        }
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransactionIn(CLINIC_A)) {
                insertMembership(connection, CLINIC_A, BRUNO, "ODONTOLOGO");
            }
        });
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransactionIn(CLINIC_A)) {
                createAccount(connection, ANA);
            }
        });
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransactionIn(CLINIC_A)) {
                insertAudit(connection, CLINIC_A, "PATIENT", UUID.randomUUID());
            }
        });
        assertThat(IdentityTestDatabase.membership(CLINIC_A, CARLA)).contains("ADMIN_CLINICA/true");
    }

    /**
     * {@code user_isolation} (TO PUBLIC) lets anyone create their own account: the restrictive
     * {@code platform_insert_check} keeps the platform to active accounts created in its name.
     */
    @Test
    void cannotCreateItsOwnAccountWithAnotherStatus() throws SQLException {
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction();
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO user_account (id, status, created_at, updated_at) VALUES (?, 'DISABLED',"
                                    + " now(), now())")) {
                statement.setObject(1, PLATFORM_ADMIN);
                statement.executeUpdate();
            }
        });
    }

    /** Not even making a cancelled membership of the platform administrator active again. */
    @Test
    void cannotReactivateAMembershipOfItsOwn() throws SQLException {
        IdentityTestDatabase.insertUser(PLATFORM_ADMIN);
        UUID own = IdentityTestDatabase.insertInvitation(CLINIC_A, PLATFORM_ADMIN, ClinicRole.ADMIN_CLINICA);
        IdentityTestDatabase.setMembershipActive(CLINIC_A, PLATFORM_ADMIN, false);

        assertRejected(INSUFFICIENT_PRIVILEGE, () -> setActive(own, true));
        assertThat(IdentityTestDatabase.membership(CLINIC_A, PLATFORM_ADMIN)).contains("ADMIN_CLINICA/false");
    }

    /** In REPEATABLE READ the trigger's check would read an old snapshot after waiting for the lock: it refuses. */
    @Test
    void refusesToRunInRepeatableRead() throws SQLException {
        assertRejected("25000", () -> {
            try (Connection connection = connectAsPlatform()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (PreparedStatement statement =
                        connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                    statement.setString(1, PLATFORM_ADMIN.toString());
                    statement.execute();
                }
                createAccount(connection, ANA);
                insertMembership(connection, CLINIC_A, ANA, "ADMIN_CLINICA");
            }
        });
    }

    /** {@code USING (accepted_at IS NULL)}: an accepted membership is not even found by the platform's UPDATE. */
    @Test
    void cannotTouchAnAcceptedMembership() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        UUID accepted = IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ADMIN_CLINICA, true);

        assertThat(setActive(accepted, false)).isZero();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, CARLA)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void seesOnlyTheAdministratorsMemberships() throws SQLException {
        IdentityTestDatabase.insertUser(CARLA);
        UUID admin = IdentityTestDatabase.insertMembership(CLINIC_B, CARLA, ClinicRole.ADMIN_CLINICA, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, CARLA, ClinicRole.ODONTOLOGO, true);

        assertThat(adminMemberships()).containsExactly(admin + "/" + CLINIC_B + "/" + CARLA + "/true/accepted");
    }

    @Test
    void recordsWhatItDoesToAMembershipOnlyUnderThatMembershipsClinic() throws SQLException {
        UUID invitation = invite(CLINIC_A, ANA);

        try (Connection connection = platformTransaction()) {
            assertThat(insertAudit(connection, CLINIC_A, "CLINIC_MEMBERSHIP", invitation))
                    .isEqualTo(1);
            connection.commit();
        }
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                insertAudit(connection, CLINIC_B, "CLINIC_MEMBERSHIP", invitation);
            }
        });
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                insertAudit(connection, CLINIC_A, "CLINIC_MEMBERSHIP", UUID.randomUUID());
            }
        });
        assertRejected(INSUFFICIENT_PRIVILEGE, () -> {
            try (Connection connection = platformTransaction()) {
                insertAudit(connection, CLINIC_A, "PATIENT", invitation);
            }
        });
    }

    /** Creates {@code user}'s account and a pending administrator membership of {@code clinic}, as the platform. */
    private static UUID invite(UUID clinic, UUID user) throws SQLException {
        try (Connection connection = platformTransaction()) {
            createAccount(connection, user);
            UUID id = insertMembership(connection, clinic, user, "ADMIN_CLINICA");
            connection.commit();
            return id;
        }
    }

    /** A platform connection in a transaction, as the platform administrator. */
    private static Connection platformTransaction() throws SQLException {
        Connection connection = connectAsPlatform();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
            statement.setString(1, PLATFORM_ADMIN.toString());
            statement.execute();
        }
        return connection;
    }

    /** A platform transaction with {@code app.tenant_id} set too, which the application never does. */
    private static Connection platformTransactionIn(UUID clinic) throws SQLException {
        Connection connection = platformTransaction();
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, clinic.toString());
            statement.execute();
        }
        return connection;
    }

    private static void createAccount(Connection connection, UUID user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT create_invited_user_account(?)")) {
            statement.setObject(1, user);
            statement.execute();
        }
    }

    private static UUID insertMembership(Connection connection, UUID clinic, UUID user, String role)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO clinic_membership (id, tenant_id, user_id, role, created_at, created_by, updated_at,"
                        + " updated_by) VALUES (?, ?, ?, ?, now(), ?, now(), ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, clinic);
            statement.setObject(3, user);
            statement.setString(4, role);
            statement.setObject(5, PLATFORM_ADMIN);
            statement.setObject(6, PLATFORM_ADMIN);
            statement.executeUpdate();
        }
        return id;
    }

    /** Cancels ({@code false}) or reactivates ({@code true}) the membership as the platform: the rows changed. */
    private static int setActive(UUID membership, boolean active) throws SQLException {
        try (Connection connection = platformTransaction();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE clinic_membership SET active = ?, updated_at = now(), updated_by = ?,"
                                + " version = version + 1 WHERE id = ?")) {
            statement.setBoolean(1, active);
            statement.setObject(2, PLATFORM_ADMIN);
            statement.setObject(3, membership);
            int changed = statement.executeUpdate();
            connection.commit();
            return changed;
        }
    }

    private static int insertAudit(Connection connection, UUID clinic, String entityType, UUID entity)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, outcome)"
                        + " VALUES (gen_random_uuid(), ?, 'PLATFORM', ?, 'UPDATE', ?, ?, 'SUCCESS')")) {
            statement.setObject(1, clinic);
            statement.setObject(2, PLATFORM_ADMIN);
            statement.setString(3, entityType);
            statement.setObject(4, entity);
            return statement.executeUpdate();
        }
    }

    private static void update(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    /** Every membership the platform sees, as {@code id/clinic/user/active/pending|accepted}. */
    private static List<String> adminMemberships() throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = platformTransaction();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, tenant_id, user_id, active, accepted_at IS NULL AS pending FROM clinic_membership"
                                + " WHERE tenant_id IN (?, ?)")) {
            statement.setObject(1, CLINIC_A);
            statement.setObject(2, CLINIC_B);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    found.add(result.getString("id") + "/" + result.getString("tenant_id") + "/"
                            + result.getString("user_id") + "/" + result.getBoolean("active") + "/"
                            + (result.getBoolean("pending") ? "pending" : "accepted"));
                }
            }
        }
        return found;
    }

    private static void assertFirstAdminRefused(SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(PSQLException.class)
                .satisfies(e -> assertFirstAdminRefusal((PSQLException) e));
    }

    /** The trigger's refusal, not another unique key. */
    private static void assertFirstAdminRefusal(PSQLException refusal) {
        assertThat(refusal.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
        assertThat(refusal.getServerErrorMessage()).isNotNull();
        assertThat(refusal.getServerErrorMessage().getConstraint()).isEqualTo("clinic_first_admin");
    }

    private static void assertRejected(String sqlState, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(sqlState));
    }

    /**
     * Waits until the other transaction waits for a lock the first one holds (a row lock not granted, in
     * {@code pg_locks}). Fails if it finishes first, which is what happens without the trigger's lock.
     */
    private static void awaitAWaitingTransaction(Future<Throwable> other)
            throws SQLException, InterruptedException, ExecutionException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try (Connection observer = connectAsOwner();
                PreparedStatement waiting = observer.prepareStatement(
                        "SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype IN ('transactionid', 'tuple')")) {
            while (true) {
                try (ResultSet result = waiting.executeQuery()) {
                    result.next();
                    if (result.getLong(1) > 0) {
                        return;
                    }
                }
                if (other.isDone()) {
                    throw new AssertionError("The other transaction did not wait for the lock: " + other.get());
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("The other transaction never waited for the lock");
                }
                Thread.sleep(10);
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
