package es.crisbr.dentasoft.shared;

import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

/**
 * Test-only: methods with {@code @SchedulerLock}, as scheduled tasks would have them, that count their runs. The first
 * holds its lock at least 30 seconds, so a second call in that time is skipped however fast the first one ends; the
 * second relies on the application's defaults.
 */
@Component
class SchedulerLockProbe {

    static final String LOCK_NAME = "test-scheduler-lock-probe";

    /** A lock with only a name, as most tasks will declare it: the application's defaults apply. */
    static final String DEFAULTS_LOCK_NAME = "test-scheduler-lock-probe-defaults";

    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicInteger runsWithDefaults = new AtomicInteger();

    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT30S", lockAtMostFor = "PT30S")
    public void run() {
        runs.incrementAndGet();
    }

    @SchedulerLock(name = DEFAULTS_LOCK_NAME)
    public void runWithDefaults() {
        runsWithDefaults.incrementAndGet();
    }

    AtomicInteger runs() {
        return runs;
    }

    AtomicInteger runsWithDefaults() {
        return runsWithDefaults;
    }
}
