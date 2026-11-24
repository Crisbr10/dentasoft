package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.ClinicProbe.NonTransactionalProbeEvent;
import es.crisbr.dentasoft.tenancy.ClinicProbe.Observation;
import es.crisbr.dentasoft.tenancy.ClinicProbe.ProbeEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work handed to another thread by Spring does not inherit the active clinic: {@code @Async} methods and module
 * listeners run on their own virtual thread and must bind the clinic themselves, when they can.
 */
@IntegrationTest
class TenantContextPropagationTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();

    @Autowired
    private ClinicProbe probe;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void asyncMethodsDoNotInheritTheClinic() throws Exception {
        Observation observation =
                TenantContext.callAs(CLINIC_A, () -> probe.observeAsync().get(10, TimeUnit.SECONDS));

        assertRunsElsewhereWithoutClinic(observation);
        // No transaction is open yet, so the method can bind its clinic and then start one.
        assertThat(observation.canBindClinic()).isTrue();
    }

    /**
     * A module listener runs in its own transaction ({@code REQUIRES_NEW}), opened before the method: it is too late
     * to bind a clinic inside.
     */
    @Test
    void moduleListenersDoNotInheritTheClinicAndCannotBindOne() throws Exception {
        Observation observation = publishWithClinicActive(ProbeEvent::new);

        assertRunsElsewhereWithoutClinic(observation);
        assertThat(observation.canBindClinic()).isFalse();
    }

    /** An {@code @Async @TransactionalEventListener} without {@code @Transactional} can bind the clinic itself. */
    @Test
    void nonTransactionalListenersDoNotInheritTheClinicButCanBindOne() throws Exception {
        Observation observation = publishWithClinicActive(NonTransactionalProbeEvent::new);

        assertRunsElsewhereWithoutClinic(observation);
        assertThat(observation.canBindClinic()).isTrue();
    }

    /** Publishes the event in a committed transaction with clinic A active and waits for the listener. */
    private Observation publishWithClinicActive(Function<CompletableFuture<Observation>, Object> event)
            throws Exception {
        CompletableFuture<Observation> observed = new CompletableFuture<>();
        TenantContext.runAs(
                CLINIC_A, () -> transaction.executeWithoutResult(status -> events.publishEvent(event.apply(observed))));
        return observed.get(10, TimeUnit.SECONDS);
    }

    private static void assertRunsElsewhereWithoutClinic(Observation observation) {
        assertThat(observation.thread()).isNotSameAs(Thread.currentThread());
        assertThat(observation.thread().isVirtual()).isTrue();
        assertThat(observation.clinicActive()).isFalse();
    }
}
