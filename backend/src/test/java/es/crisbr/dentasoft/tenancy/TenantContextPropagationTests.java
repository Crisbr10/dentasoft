package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.ClinicProbe.Observation;
import es.crisbr.dentasoft.tenancy.ClinicProbe.ProbeEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work handed to another thread by Spring does not inherit the active clinic: {@code @Async} methods and
 * {@code @ApplicationModuleListener} listeners run on their own thread and must bind the clinic themselves.
 */
@IntegrationTest
class TenantContextPropagationTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();

    @Autowired
    private ClinicProbe probe;

    @Test
    void asyncMethodsDoNotInheritTheClinic() throws Exception {
        Observation observation =
                TenantContext.callAs(CLINIC_A, () -> probe.observeAsync().get(10, TimeUnit.SECONDS));

        assertThat(observation.thread()).isNotSameAs(Thread.currentThread());
        assertThat(observation.clinicActive()).isFalse();
        // No transaction is open yet, so the method can bind its clinic and then start one.
        assertThat(observation.canBindClinic()).isTrue();
    }

    /**
     * A module listener runs in its own transaction ({@code REQUIRES_NEW}), opened before the method: it is too late
     * to bind a clinic inside. The listener must call, inside {@code runAs}, a service that starts the transaction.
     */
    @Test
    void moduleListenersDoNotInheritTheClinic(
            @Autowired ApplicationEventPublisher events, @Autowired TransactionTemplate transaction) throws Exception {
        CompletableFuture<Observation> observed = new CompletableFuture<>();

        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(status -> events.publishEvent(new ProbeEvent(observed))));

        Observation observation = observed.get(10, TimeUnit.SECONDS);
        assertThat(observation.thread()).isNotSameAs(Thread.currentThread());
        assertThat(observation.clinicActive()).isFalse();
        assertThat(observation.canBindClinic()).isFalse();
    }
}
