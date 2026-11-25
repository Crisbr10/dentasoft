package es.crisbr.dentasoft.shared;

import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

/**
 * Test-only: a method with {@code @SchedulerLock}, as a scheduled task would have it, that counts its runs. The lock is
 * held at least 30 seconds, so a second call in that time is skipped however fast the first one ends.
 */
@Component
class SchedulerLockProbe {

    static final String LOCK_NAME = "test-scheduler-lock-probe";

    private final AtomicInteger runs = new AtomicInteger();

    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT30S", lockAtMostFor = "PT30S")
    public void run() {
        runs.incrementAndGet();
    }

    AtomicInteger runs() {
        return runs;
    }
}
