package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.TestData;
import java.util.concurrent.CompletableFuture;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Test-only: reports, from {@code @Async} methods and module listeners, whether a clinic is active, on which thread
 * they run and whether they can bind a clinic themselves.
 */
@Component
class ClinicProbe {

    /** Published by the tests; the listener completes {@code observed}. */
    record ProbeEvent(CompletableFuture<Observation> observed) {}

    record Observation(boolean clinicActive, Thread thread, boolean canBindClinic) {

        static Observation now() {
            return new Observation(TenantContext.isBound(), Thread.currentThread(), canBind());
        }

        private static boolean canBind() {
            try {
                TenantContext.runAs(TestData.CLINIC_A.id(), () -> {});
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }
    }

    @Async
    CompletableFuture<Observation> observeAsync() {
        return CompletableFuture.completedFuture(Observation.now());
    }

    @ApplicationModuleListener
    void on(ProbeEvent event) {
        event.observed().complete(Observation.now());
    }
}
