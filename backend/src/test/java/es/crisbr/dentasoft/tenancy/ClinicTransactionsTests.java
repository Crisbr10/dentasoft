package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.ClinicListenerProbe.RecordRequested;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ClinicTransactions} binds a clinic and opens a transaction of its own inside it (M01-T08): the way for scheduled
 * tasks and event listeners to work with a clinic's data.
 */
@IntegrationTest
class ClinicTransactionsTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private ClinicTransactions transactions;

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
    }

    @AfterEach
    void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAndRecords();
    }

    @Test
    void runsWithTheClinicActiveInsideATransaction() throws SQLException {
        UUID id = transactions.call(CLINIC_A, () -> {
            assertThat(TenantContext.current()).isEqualTo(CLINIC_A);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            return records.save(new TenancyTestRecord("stored")).getId();
        });

        assertThat(TenancyTestDatabase.stored(CLINIC_A, id)).isPresent();
        assertThat(TenancyTestDatabase.countStored(CLINIC_B)).isZero();
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void aFailureRollsBackTheClinicsWork() throws SQLException {
        assertThatThrownBy(() -> transactions.run(CLINIC_A, () -> {
                    records.save(new TenancyTestRecord("rolled back"));
                    throw new IllegalStateException("Simulated failure");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenancyTestDatabase.countStored(CLINIC_A)).isZero();
    }

    @Test
    void refusesToRunInsideAnActiveClinicOrATransaction() throws SQLException {
        Runnable store = () -> records.save(new TenancyTestRecord("never stored"));

        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, () -> transactions.run(CLINIC_A, store)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> transactions.run(CLINIC_A, store)))
                .isInstanceOf(IllegalStateException.class);
        // SUPPORTS opens no transaction, but its entity manager, without a clinic, would be reused.
        TransactionTemplate supports = new TransactionTemplate(transaction.getTransactionManager());
        supports.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        assertThatThrownBy(() -> supports.executeWithoutResult(status -> transactions.run(CLINIC_A, store)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenancyTestDatabase.countStored(CLINIC_A)).isZero();
    }

    /** The event carries the clinic; the listener binds it and opens its own transaction. */
    @Test
    void aListenerWorksWithTheClinicOfTheEvent() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();

        transactions.run(CLINIC_A, () -> events.publishEvent(new RecordRequested(CLINIC_A, "from event", false, done)));
        done.get(10, TimeUnit.SECONDS);

        assertThat(TenancyTestDatabase.countStored(CLINIC_A)).isOne();
        assertThat(TenancyTestDatabase.countStored(CLINIC_B)).isZero();
    }

    /** No retry: the listener's work is rolled back and the event is lost (ADR 0013). */
    @Test
    void aFailedListenerRollsBackItsWork() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();

        transactions.run(CLINIC_A, () -> events.publishEvent(new RecordRequested(CLINIC_A, "from event", true, done)));

        assertThatThrownBy(() -> done.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(TenancyTestDatabase.countStored(CLINIC_A)).isZero();
    }
}
