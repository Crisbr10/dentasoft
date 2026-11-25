package es.crisbr.dentasoft.shared;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Test-only: work that runs outside any request and fails with a message that carries a value, as an exception
 * message may carry personal data. The listener fails on every {@link Event}; the scheduled task does nothing until
 * {@link #failNextScheduledRun} arms it, and then fails once.
 */
@Component
class FailingTaskProbe {

    /** Published inside a transaction, so the listener receives it once it commits. */
    record Event(String value) {}

    private final AtomicReference<String> nextScheduledFailure = new AtomicReference<>();

    @ApplicationModuleListener
    void on(Event event) {
        throw new IllegalStateException("listener failed for " + event.value());
    }

    @Scheduled(fixedDelay = 100, timeUnit = TimeUnit.MILLISECONDS)
    void run() {
        String value = nextScheduledFailure.getAndSet(null);
        if (value != null) {
            throw new IllegalStateException("scheduled task failed for " + value);
        }
    }

    void failNextScheduledRun(String value) {
        nextScheduledFailure.set(value);
    }
}
