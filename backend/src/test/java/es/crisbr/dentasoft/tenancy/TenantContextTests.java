package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import es.crisbr.dentasoft.TestData;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The active clinic lives only inside its block, on its own thread, with virtual threads as in the application. */
class TenantContextTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Test
    void noClinicIsActiveOutsideABlock() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThatExceptionOfType(NoActiveClinicException.class).isThrownBy(TenantContext::current);
    }

    @Test
    void clinicIsActiveOnlyInsideTheBlock() {
        AtomicReference<UUID> inside = new AtomicReference<>();

        TenantContext.runAs(CLINIC_A, () -> inside.set(TenantContext.current()));

        assertThat(inside).hasValue(CLINIC_A);
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void clinicIsClearedWhenTheBlockFails() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.runAs(CLINIC_A, () -> {
                    throw new IllegalStateException("failure inside the block");
                }));

        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void callAsReturnsTheResultAndPropagatesCheckedExceptions() {
        assertThat(TenantContext.callAs(CLINIC_A, TenantContext::current)).isEqualTo(CLINIC_A);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> TenantContext.callAs(CLINIC_A, () -> {
                    throw new IOException("checked failure inside the block");
                }));
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void rejectsAMissingClinicAndTheNoClinicMarker() {
        assertThatIllegalArgumentException().isThrownBy(() -> TenantContext.runAs(null, () -> {}));
        assertThatIllegalArgumentException().isThrownBy(() -> TenantContext.runAs(new UUID(0L, 0L), () -> {}));
    }

    @Test
    void nestingTheSameClinicIsAllowed() {
        UUID inner = TenantContext.callAs(CLINIC_A, () -> TenantContext.callAs(CLINIC_A, TenantContext::current));

        assertThat(inner).isEqualTo(CLINIC_A);
    }

    @Test
    void nestingAnotherClinicIsRejected() {
        AtomicReference<UUID> afterRejection = new AtomicReference<>();

        TenantContext.runAs(CLINIC_A, () -> {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TenantContext.runAs(CLINIC_B, () -> {}))
                    .withMessageContaining("another clinic");
            afterRejection.set(TenantContext.current());
        });

        assertThat(afterRejection).hasValue(CLINIC_A);
    }

    /** The case a ThreadLocal gets wrong: one reused thread runs a task with a clinic, then a task without one. */
    @Test
    void clinicDoesNotLeakToTheNextTaskOfTheSameExecutor() throws Exception {
        try (ExecutorService executor =
                Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            Future<Thread> first = executor.submit(() -> TenantContext.callAs(CLINIC_A, Thread::currentThread));
            Future<Thread> second = executor.submit(Thread::currentThread);
            Future<Boolean> secondSeesClinic = executor.submit(TenantContext::isBound);

            assertThat(second.get()).isSameAs(first.get());
            assertThat(secondSeesClinic.get()).isFalse();
        }
    }

    @Test
    void tasksSubmittedFromInsideTheBlockDoNotInheritTheClinic() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> taskSeesClinic =
                    TenantContext.callAs(CLINIC_A, () -> executor.submit(TenantContext::isBound));

            assertThat(taskSeesClinic.get()).isFalse();
        }
    }

    @Test
    void newThreadsDoNotInheritTheClinic() throws Exception {
        AtomicBoolean virtualSeesClinic = new AtomicBoolean(true);
        AtomicBoolean platformSeesClinic = new AtomicBoolean(true);

        TenantContext.runAs(CLINIC_A, () -> {
            try {
                Thread.ofVirtual()
                        .start(() -> virtualSeesClinic.set(TenantContext.isBound()))
                        .join();
                Thread.ofPlatform()
                        .start(() -> platformSeesClinic.set(TenantContext.isBound()))
                        .join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        assertThat(virtualSeesClinic).isFalse();
        assertThat(platformSeesClinic).isFalse();
    }

    /** Many virtual threads at once, each with its own clinic, all waiting together before they check it. */
    @Test
    void concurrentVirtualThreadsEachSeeTheirOwnClinic() throws Exception {
        int tasks = 1_000;
        CountDownLatch allBound = new CountDownLatch(tasks);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                UUID clinic = UUID.randomUUID();
                results.add(CompletableFuture.supplyAsync(
                        () -> TenantContext.callAs(clinic, () -> {
                            allBound.countDown();
                            awaitQuietly(allBound);
                            return clinic.equals(TenantContext.current());
                        }),
                        executor));
            }
            for (CompletableFuture<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS))
                    .as("all tasks bound their clinic")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
