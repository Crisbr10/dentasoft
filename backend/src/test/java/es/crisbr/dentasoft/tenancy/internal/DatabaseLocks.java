package es.crisbr.dentasoft.tenancy.internal;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Lets a concurrency test see that a transaction is waiting for a lock another holds, without fixed sleeps: in
 * {@code pg_locks}, which every role may read, a lock not granted is one its backend is waiting for.
 */
final class DatabaseLocks {

    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private DatabaseLocks() {}

    /** The backend of the transaction {@code entityManager} takes part in. */
    static int backendPid(EntityManager entityManager) {
        return ((Number) entityManager
                        .createNativeQuery("SELECT pg_backend_pid()")
                        .getSingleResult())
                .intValue();
    }

    /**
     * Whether the backend {@code pid} is waiting for a row another transaction holds (a transaction or tuple lock, not
     * a short internal one such as extending a table); {@code false} while {@code pid} is not known (0).
     */
    static boolean isWaitingForALock(int pid) {
        if (pid == 0) {
            return false;
        }
        try (Connection connection = connectAsOwner();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM pg_locks WHERE pid = ? AND NOT granted AND locktype IN ('transactionid', 'tuple')")) {
            statement.setInt(1, pid);
            try (ResultSet waiting = statement.executeQuery()) {
                waiting.next();
                return waiting.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the backend {@code pid} is waiting for a lock. */
    static void awaitWaitingForALock(IntSupplier pid) {
        awaitUntil(() -> isWaitingForALock(pid.getAsInt()), "The other transaction never waited for a lock");
    }

    /** Waits, polling, until {@code condition} holds; fails after {@link #TIMEOUT}. */
    static void awaitUntil(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(failure);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
