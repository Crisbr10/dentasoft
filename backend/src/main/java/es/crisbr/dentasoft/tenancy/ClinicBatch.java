package es.crisbr.dentasoft.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a process without a user clinic by clinic, for every active clinic (M01-T08, ADR 0013): a scheduled task, for
 * example. Suspended clinics are skipped.
 *
 * <p>Each clinic runs with {@link ClinicTransactions}: its clinic active and a transaction of its own, so a failure
 * only rolls back that clinic's work. A {@link RuntimeException} is logged with the clinic's id and the exception's
 * type, and the process goes on with the next clinic; the message is never logged, since it can carry personal data.
 * An {@link Error}, such as {@code OutOfMemoryError}, is not caught: it stops the process.
 *
 * <p>Changes are recorded in the audit log as {@code SYSTEM}: there is no user.
 */
@Component
public class ClinicBatch {

    private static final Logger log = LoggerFactory.getLogger(ClinicBatch.class);

    private final ActiveClinicIds clinics;
    private final ClinicTransactions transactions;

    ClinicBatch(ActiveClinicIds clinics, ClinicTransactions transactions) {
        this.clinics = clinics;
        this.transactions = transactions;
    }

    /**
     * Runs {@code task} once for every active clinic, in id order.
     *
     * @param job a short, fixed name for the logs, such as {@code appointment-reminders}; never data
     * @return which clinics succeeded and which failed
     */
    public Result forEachActiveClinic(String job, ClinicTask task) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(task, "task");
        if (TenantContext.isBound() || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A clinic batch runs each clinic in its own transaction: start it without an active clinic or"
                            + " transaction");
        }
        List<UUID> succeeded = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        for (UUID clinicId : clinics.activeClinicIds()) {
            try {
                transactions.run(clinicId, () -> task.run(clinicId));
                succeeded.add(clinicId);
            } catch (RuntimeException e) {
                String errorType = e.getClass().getName();
                log.warn("Job {} failed for clinic {}: {}", job, clinicId, errorType);
                failed.add(new Failure(clinicId, errorType));
            }
        }
        Result result = new Result(job, succeeded, failed);
        if (failed.isEmpty()) {
            log.info("Job {} finished: {} clinics", job, succeeded.size());
        } else {
            log.warn(
                    "Job {} finished: {} clinics, {} failed: {}",
                    job,
                    succeeded.size() + failed.size(),
                    failed.size(),
                    result.failedClinicIds());
        }
        return result;
    }

    /** The work of one clinic, with that clinic active and inside its transaction. */
    @FunctionalInterface
    public interface ClinicTask {

        void run(UUID clinicId);
    }

    /** The outcome of a batch: the clinics that succeeded and those that failed, in the order they ran. */
    public record Result(String job, List<UUID> succeeded, List<Failure> failed) {

        public Result {
            succeeded = List.copyOf(succeeded);
            failed = List.copyOf(failed);
        }

        public List<UUID> failedClinicIds() {
            return failed.stream().map(Failure::clinicId).toList();
        }
    }

    /** A clinic whose work failed, with the fully qualified name of the exception's class: never its message. */
    public record Failure(UUID clinicId, String errorType) {}
}
