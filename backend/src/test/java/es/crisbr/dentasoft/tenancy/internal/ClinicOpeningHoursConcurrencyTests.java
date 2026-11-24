package es.crisbr.dentasoft.tenancy.internal;

import static es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHoursTests.slot;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * the other has done its work too or its backend is waiting for a lock. Without the lock both reach that point: each deletes the
 * old week, both insert theirs, and the week left is the two mixed.
 */
class ClinicOpeningHoursConcurrencyTests extends ClinicConfigurationTestBase {

    private static final List<Slot> OLD_WEEK = List.of(slot(MONDAY, "08:00", "15:00"));
    private static final List<Slot> FIRST_WEEK =
            List.of(slot(MONDAY, "09:00", "14:00"), slot(TUESDAY, "09:00", "14:00"));
    private static final List<Slot> SECOND_WEEK =
            List.of(slot(MONDAY, "10:00", "13:00"), slot(WEDNESDAY, "16:00", "20:00"));

    @Autowired
    private ClinicOpeningHours openingHours;

    @Autowired
    private TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void twoSimultaneousReplacementsLeaveExactlyOneOfTheTwoWeeks() throws Exception {
        asClinicA(() -> openingHours.replaceWeek(OLD_WEEK));
        CyclicBarrier start = new CyclicBarrier(2);
        Replacement first = new Replacement(FIRST_WEEK);
        Replacement second = new Replacement(SECOND_WEEK);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<List<Slot>> firstWeek = threads.submit(replacement(first, second, start));
            Future<List<Slot>> secondWeek = threads.submit(replacement(second, first, start));

            assertThat(firstWeek.get(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isEqualTo(FIRST_WEEK);
            assertThat(secondWeek.get(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isEqualTo(SECOND_WEEK);
        } finally {
            threads.shutdownNow();
            // A worker still inside its transaction would hold locks the cleanup after the test needs.
            threads.awaitTermination(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        List<Slot> week = asClinicA(openingHours::week);
        assertThat(week).isIn(FIRST_WEEK, SECOND_WEEK);
        assertThatCode(() -> ClinicOpeningHours.requireNoOverlaps(week)).doesNotThrowAnyException();
    }

    /** One of the two replacements: its week, its backend once its transaction has begun, and whether it is done. */
    private record Replacement(List<Slot> week, AtomicInteger pid, CountDownLatch done) {

        Replacement(List<Slot> week) {
            this(week, new AtomicInteger(), new CountDownLatch(1));
        }
    }

    /**
     * Replaces the week as clinic A's administrator, in a transaction that commits only once the {@code other}
     * replacement has done its work or its backend is waiting for a lock.
     */
    private Callable<List<Slot>> replacement(Replacement own, Replacement other, CyclicBarrier start) {
        return new DelegatingSecurityContextCallable<>(() -> TenantContext.callAs(
                CLINIC_A,
                () -> transaction.execute(status -> {
                    own.pid().set(DatabaseLocks.backendPid(entityManager));
                    await(start);
                    List<Slot> replaced = openingHours.replaceWeek(own.week());
                    own.done().countDown();
                    DatabaseLocks.awaitUntil(
                            () -> other.done().getCount() == 0
                                    || DatabaseLocks.isWaitingForALock(
                                            other.pid().get()),
                            "The other replacement neither finished nor waited for a lock");
                    return replaced;
                })));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The other replacement did not start", e);
        }
    }
}
