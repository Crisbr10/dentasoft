package es.crisbr.dentasoft.tenancy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work of one clinic in a process without a user (a scheduled task, an event listener): binds the clinic with
 * {@link TenantContext#runAs} and, inside, opens a transaction of its own, which commits or rolls back that clinic's
 * work alone.
 *
 * <p>It is the way for an event listener to work with a clinic's data: the event carries the clinic's id, and the
 * listener, declared with {@code @Async} and {@code @TransactionalEventListener} but without {@code @Transactional},
 * calls {@link #run} with it. An {@code @ApplicationModuleListener} cannot, as its transaction is already open when the
 * method starts (see {@code docs/plan/M01-clinicas-multitenant.md}). If the listener fails, the event is lost: there is
 * no retry until the event publication registry is turned on (ADR 0013).
 *
 * <p>Refuses to run with a clinic already active or inside a transaction: the clinic must be bound before the
 * transaction begins, and one transaction must never span several clinics.
 */
@Component
public class ClinicTransactions {

    private final TransactionTemplate transaction;

    ClinicTransactions(PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Runs {@code work} with {@code clinicId} active, in a transaction of its own. */
    public void run(UUID clinicId, Runnable work) {
        Objects.requireNonNull(work, "work");
        call(clinicId, () -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} with {@code clinicId} active, in a transaction of its own, and returns its result. */
    public <T> T call(UUID clinicId, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (TenantContext.isBound()) {
            throw new IllegalStateException("Cannot start a clinic's work inside an active clinic");
        }
        // Synchronization is active without an actual transaction too, in a SUPPORTS scope: its entity manager, opened
        // without a clinic, would be reused for this clinic's work.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Cannot start a clinic's work inside a transaction");
        }
        return TenantContext.callAs(clinicId, () -> transaction.execute(status -> work.get()));
    }
}
