package es.crisbr.dentasoft.identity.internal;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.internal.PractitionerConflictException.Reason;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * A membership deactivated while its user's practitioner record is being created (M02-T06). The creation locks the
 * membership while checking it ({@code FOR SHARE}), so it waits for the deactivation and then sees it: without the
 * lock it would read the membership as it was before, and leave an active record of someone who left the clinic.
 */
@IntegrationTest
class PractitionerMembershipRaceTests extends ClinicUsersTestBase {

    private static final long TIMEOUT_SECONDS = 15;

    @Autowired
    private PractitionerRoles practitioners;

    @Test
    void aRecordCreatedWhileTheMembershipIsBeingDeactivatedIsRefused() throws Exception {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection deactivation = connectAsOwner()) {
            deactivation.setAutoCommit(false);
            setClinic(deactivation);
            try (PreparedStatement statement =
                    deactivation.prepareStatement("UPDATE clinic_membership SET active = false WHERE user_id = ?")) {
                statement.setObject(1, dentist);
                assertThat(statement.executeUpdate()).isOne();
            }

            Future<Throwable> creation = executor.submit(() -> {
                try {
                    asAdmin(() -> practitioners.create(new PractitionerData(
                            dentist, "Dr. Pruebas", PractitionerSpecialty.ORTHODONTICS, "28", "1234", "#1E88E5")));
                    return null;
                } catch (RuntimeException refused) {
                    return refused;
                }
            });
            awaitAWaitingTransaction(creation);
            deactivation.commit();

            assertThat(creation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            PractitionerConflictException.class,
                            conflict -> assertThat(conflict.reason()).isEqualTo(Reason.USER_NOT_ELIGIBLE));
        } finally {
            executor.shutdownNow();
        }
        assertThat(IdentityTestDatabase.membership(CLINIC_A, dentist)).contains("ODONTOLOGO/false");
        assertThat(asAdmin(() -> practitioners.list(null, PageRequest.of(0, 20))))
                .isEmpty();
    }

    private static void setClinic(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, CLINIC_A.toString());
            statement.execute();
        }
    }

    /**
     * Waits until the creation waits for the row the deactivation holds: in {@code pg_locks}, a row lock not granted.
     * Fails if the creation finishes first, which is what happens without the lock.
     */
    private static void awaitAWaitingTransaction(Future<Throwable> creation)
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
                if (creation.isDone()) {
                    throw new AssertionError("The creation did not wait for the membership: " + creation.get());
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("The creation never waited for the membership");
                }
                Thread.sleep(10);
            }
        }
    }
}
