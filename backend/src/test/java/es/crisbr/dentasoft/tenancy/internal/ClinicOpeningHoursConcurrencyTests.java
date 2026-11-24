package es.crisbr.dentasoft.tenancy.internal;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHoursTests.slot;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two administrators of the same clinic replace its week at the same time (M01-T06). Each replacement is valid on its
 * own, but the two together overlap on Monday: the clinic's row lock makes one wait for the other, so the week left is
 * exactly one of the two.
 *
 * <p>Both replacements start together at a barrier. Each then holds its transaction open, before committing, until
 * the other has done its work too or is waiting for a lock. Without the lock both reach that point: each deletes the
 * old week, both insert theirs, and the week left is the two mixed.
 */
class ClinicOpeningHoursConcurrencyTests extends ClinicConfigurationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final List<Slot> OLD_WEEK = List.of(slot(MONDAY, "08:00", "15:00"));
    private static final List<Slot> FIRST_WEEK =
            List.of(slot(MONDAY, "09:00", "14:00"), slot(TUESDAY, "09:00", "14:00"));
    private static final List<Slot> SECOND_WEEK =
            List.of(slot(MONDAY, "10:00", "13:00"), slot(WEDNESDAY, "16:00", "20:00"));

    @Autowired
    private ClinicOpeningHours openingHours;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void twoSimultaneousReplacementsLeaveExactlyOneOfTheTwoWeeks() throws Exception {
        asClinicA(() -> openingHours.replaceWeek(OLD_WEEK));
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<List<Slot>> first = threads.submit(replacement(FIRST_WEEK, start, firstDone, secondDone));
            Future<List<Slot>> second = threads.submit(replacement(SECOND_WEEK, start, secondDone, firstDone));

            assertThat(first.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(FIRST_WEEK);
            assertThat(second.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(SECOND_WEEK);
        } finally {
            threads.shutdownNow();
        }

        List<Slot> week = asClinicA(openingHours::week);
        assertThat(week).isIn(FIRST_WEEK, SECOND_WEEK);
        assertThatCode(() -> ClinicOpeningHours.requireNoOverlaps(week)).doesNotThrowAnyException();
    }

    /**
     * Replaces the week as clinic A's administrator, in a transaction that commits only once the other replacement has
     * done its work ({@code otherDone}) or is waiting for a lock.
     */
    private Callable<List<Slot>> replacement(
            List<Slot> week, CyclicBarrier start, CountDownLatch done, CountDownLatch otherDone) {
        return new DelegatingSecurityContextCallable<>(() -> TenantContext.callAs(
                CLINIC_A,
                () -> transaction.execute(status -> {
                    await(start);
                    List<Slot> replaced = openingHours.replaceWeek(week);
                    done.countDown();
                    awaitDoneOrWaitingForALock(otherDone);
                    return replaced;
                })));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The other replacement did not start", e);
        }
    }

    private static void awaitDoneOrWaitingForALock(CountDownLatch otherDone) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (!otherDone.await(10, TimeUnit.MILLISECONDS)) {
                if (someoneIsWaitingForALock()) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The other replacement neither finished nor waited for a lock");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code pg_locks} is readable by every role; a lock not granted is a transaction waiting for another. */
    private static boolean someoneIsWaitingForALock() {
        try (Connection connection = connectAsOwner();
                Statement statement = connection.createStatement();
                ResultSet waiting = statement.executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted")) {
            waiting.next();
            return waiting.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
