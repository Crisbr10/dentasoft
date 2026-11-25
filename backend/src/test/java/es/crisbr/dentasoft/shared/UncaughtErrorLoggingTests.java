package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import es.crisbr.dentasoft.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An exception that escapes an {@code @Async} listener or a scheduled task is logged with its classes and stack frames
 * but without its messages (#123): no request handles it, so nothing else would keep the values it carries, which may
 * be personal data, out of the log.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class UncaughtErrorLoggingTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private FailingTaskProbe probe;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void anAsyncListenerThatFailsLeavesTheValueOutOfTheLog(CapturedOutput output) {
        String value = "listener 600 731 774";

        transaction.executeWithoutResult(status -> events.publishEvent(new FailingTaskProbe.Event(value)));

        await().atMost(TIMEOUT).until(() -> output.getAll().contains(FailingTaskProbe.class.getName() + ".on"));
        assertThat(output.getAll())
                .contains(IllegalStateException.class.getName())
                .doesNotContain(value);
    }

    @Test
    void aScheduledTaskThatFailsLeavesTheValueOutOfTheLog(CapturedOutput output) {
        String value = "scheduled 600 731 775";

        probe.failNextScheduledRun(value);

        await().atMost(TIMEOUT).until(() -> output.getAll().contains(FailingTaskProbe.class.getName() + ".run"));
        assertThat(output.getAll())
                .contains(IllegalStateException.class.getName())
                .doesNotContain(value);
    }
}
