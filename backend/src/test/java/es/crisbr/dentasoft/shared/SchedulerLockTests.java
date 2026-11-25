package es.crisbr.dentasoft.shared;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A scheduled task runs once even with several replicas (M01-T08, ADR 0013): ShedLock takes a lock row in
 * {@code scheduler.shedlock} as the application role before running it.
 */
@IntegrationTest
class SchedulerLockTests {

    /** Long enough for both replicas to try while the first one still holds the lock. */
    private static final Duration HELD_FOR = Duration.ofSeconds(30);

    private final String lockName = "test-" + UUID.randomUUID();

    @Autowired
    private LockProvider applicationLockProvider;

    @Autowired
    private SchedulerLockProbe probe;

    @AfterEach
    void deleteLock() throws SQLException {
        try (Connection connection = connectAsOwner();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM scheduler.shedlock WHERE name LIKE 'test-%'")) {
            statement.executeUpdate();
        }
    }

    private static final Replica EAST = new Replica("replica-east", "Pacific/Kiritimati");
    private static final Replica WEST = new Replica("replica-west", "Pacific/Pago_Pago");

    /**
     * Two replicas, each with its own scheduler and its own connections to the same database, run the same task at the
     * same moment: only one of them runs it.
     */
    @Test
    void twoSchedulersOnTheSameDatabaseRunTheTaskOnce() throws Exception {
        List<String> ranOn = runOnReplicas(lockName, Duration.ZERO, EAST, WEST);

        assertThat(ranOn).hasSize(1);
        assertThat(lockedBy(lockName)).isEqualTo(ranOn.getFirst());
    }

    /**
     * The sessions of the two replicas are in time zones 25 hours apart, as replicas in different zones would be. The
     * lock still holds whichever takes it first, because its times are UTC: with {@code timestamptz} columns, a lock
     * taken in the eastern zone would look expired to the western one, which would run the task again.
     */
    @Test
    void aLockHoldsForReplicasInAnyTimeZone() throws Exception {
        for (List<Replica> order : List.of(List.of(EAST, WEST), List.of(WEST, EAST))) {
            String name = lockName + "-" + order.getFirst().name();

            List<String> ranOn = runOnReplicas(name, Duration.ofSeconds(1), order.toArray(Replica[]::new));

            assertThat(ranOn)
                    .as("replicas that ran the task, %s first", order.getFirst())
                    .containsExactly(order.getFirst().name());
        }
    }

    /** {@code @SchedulerLock} on a bean method goes through the application's lock provider. */
    @Test
    void annotatedMethodsTakeTheApplicationLock() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                awaitQuietly(start);
                probe.run();
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(10));
        }

        assertThat(probe.runs()).hasValue(1);
        assertThat(lockRowExists(SchedulerLockProbe.LOCK_NAME)).isTrue();
    }

    /**
     * A short task is over before the other replica's trigger fires, a few milliseconds later: the default
     * {@code lockAtLeastFor} keeps the lock until then, so the late replica skips it.
     */
    @Test
    void aShortTaskWithTheDefaultLockDoesNotRunAgainOnALateReplica() {
        probe.runWithDefaults();
        probe.runWithDefaults();

        assertThat(probe.runsWithDefaults()).hasValue(1);
    }

    @Test
    void theApplicationLockProviderUsesTheLockTable() throws SQLException {
        assertThat(applicationLockProvider.lock(configuration(lockName))).isPresent();
        assertThat(lockRowExists(lockName)).isTrue();
    }

    /** Each replica, in order, tries to run the task {@code stagger} after the previous one. */
    private static List<String> runOnReplicas(String name, Duration stagger, Replica... replicas)
            throws InterruptedException {
        ConcurrentLinkedQueue<String> ranOn = new ConcurrentLinkedQueue<>();
        CountDownLatch finished = new CountDownLatch(replicas.length);
        Instant at = Instant.now().plusMillis(500);
        List<ThreadPoolTaskScheduler> schedulers = new ArrayList<>();
        try {
            for (int i = 0; i < replicas.length; i++) {
                Replica replica = replicas[i];
                LockProvider lockProvider =
                        SchedulingConfiguration.lockProvider(new ReplicaDataSource(replica.timeZone()), replica.name());
                DefaultLockingTaskExecutor executor = new DefaultLockingTaskExecutor(lockProvider);
                ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                scheduler.setThreadNamePrefix(replica.name() + "-");
                scheduler.initialize();
                schedulers.add(scheduler);
                scheduler.schedule(
                        () -> {
                            try {
                                executor.executeWithLock(
                                        (Runnable) () -> ranOn.add(replica.name()), configuration(name));
                            } finally {
                                finished.countDown();
                            }
                        },
                        at.plus(stagger.multipliedBy(i)));
            }
            assertThat(finished.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        }
        return List.copyOf(ranOn);
    }

    private static LockConfiguration configuration(String name) {
        return new LockConfiguration(Instant.now(), name, HELD_FOR, HELD_FOR);
    }

    private static String lockedBy(String name) throws SQLException {
        try (Connection connection = connectAsOwner();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT locked_by FROM scheduler.shedlock WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static boolean lockRowExists(String name) throws SQLException {
        try (Connection connection = connectAsOwner();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM scheduler.shedlock WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) == 1;
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Replica(String name, String timeZone) {}

    /**
     * The connections of one replica: as the application role, each a new one, with the session in {@code timeZone}.
     */
    private static final class ReplicaDataSource extends AbstractDataSource {

        private final String timeZone;

        ReplicaDataSource(String timeZone) {
            this.timeZone = timeZone;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = connectAsApplication();
            try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")) {
                statement.setString(1, timeZone);
                statement.execute();
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }
    }
}
