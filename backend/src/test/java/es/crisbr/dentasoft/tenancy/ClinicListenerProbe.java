package es.crisbr.dentasoft.tenancy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Test-only: a listener that works with a clinic's data as {@code docs/plan/M01-clinicas-multitenant.md} describes. The
 * event carries the clinic's id; the listener, without a transaction of its own, runs its work with
 * {@link ClinicTransactions}.
 */
@Component
class ClinicListenerProbe {

    /**
     * Asks the listener to store a record with {@code label} in {@code clinicId}, and to fail afterwards when
     * {@code fail} is set. {@code done} completes when the listener has finished.
     */
    record RecordRequested(UUID clinicId, String label, boolean fail, CompletableFuture<Void> done) {}

    private final ClinicTransactions transactions;
    private final TenancyTestRecordRepository records;

    ClinicListenerProbe(ClinicTransactions transactions, TenancyTestRecordRepository records) {
        this.transactions = transactions;
        this.records = records;
    }

    @Async
    @TransactionalEventListener
    void on(RecordRequested event) {
        try {
            transactions.run(event.clinicId(), () -> {
                records.save(new TenancyTestRecord(event.label()));
                if (event.fail()) {
                    throw new IllegalStateException("Simulated failure of the listener");
                }
            });
            event.done().complete(null);
        } catch (RuntimeException e) {
            event.done().completeExceptionally(e);
        }
    }
}
