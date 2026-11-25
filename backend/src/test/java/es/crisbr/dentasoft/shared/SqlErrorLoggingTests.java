package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A statement that fails through Hibernate leaves the values it carried out of the log and out of the exception's
 * message (#123). PostgreSQL may repeat a value in the detail of its error, as with a unique key, or in the error
 * itself, as with a value of the wrong type; Hibernate logs the error of every failed statement.
 *
 * <p>Tables with row level security already get no key values in the detail: PostgreSQL leaves them out. The lock
 * table of the scheduled tasks has none, so its key shows what any table without it would give.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SqlErrorLoggingTests {

    /** Invented, and unusual enough that it appears in the output only if a failed statement put it there. */
    private static final String VALUE = "ana.zafiro.7731@ejemplo.test";

    private static final String INSERT_LOCK = """
            INSERT INTO scheduler.shedlock (name, lock_until, locked_at, locked_by)
            VALUES (:name, now(), now(), 'sql-error-logging-test')""";

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void aUniqueKeyViolationLeavesTheValueOutOfTheLog(CapturedOutput output) {
        // The same transaction, which the failure rolls back: no lock row is left behind.
        Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            insertLock();
            insertLock();
        }));

        assertThat(sqlState(failure)).isEqualTo("23505");
        assertThat(output.getAll()).doesNotContain(VALUE);
        assertMessagesLeaveOut(failure);
    }

    @Test
    void aValueInTheErrorItselfStaysOutOfTheLog(CapturedOutput output) {
        Throwable failure = catchThrowable(() -> transaction.execute(status -> entityManager
                .createNativeQuery("SELECT CAST(:value AS uuid)")
                .setParameter("value", VALUE)
                .getSingleResult()));

        // invalid_text_representation, whose message quotes the value.
        assertThat(sqlState(failure)).isEqualTo("22P02");
        assertThat(output.getAll()).doesNotContain(VALUE);
    }

    private void insertLock() {
        entityManager.createNativeQuery(INSERT_LOCK).setParameter("name", VALUE).executeUpdate();
    }

    private static void assertMessagesLeaveOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(VALUE);
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
