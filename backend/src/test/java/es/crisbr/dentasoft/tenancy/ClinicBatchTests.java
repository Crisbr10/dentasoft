package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.tenancy.ClinicBatch.Failure;
import es.crisbr.dentasoft.tenancy.ClinicBatch.Result;
import es.crisbr.dentasoft.tenancy.persistence.Location;
import es.crisbr.dentasoft.tenancy.persistence.LocationRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A process without a user runs clinic by clinic (M01-T08): every active clinic, never a suspended one, each with its
 * clinic active and a transaction of its own, so a failure in one clinic does not stop the others.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ClinicBatchTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    /** After A and B in id order. */
    private static final TestData.Clinic CLINIC_C =
            new TestData.Clinic(UUID.fromString("0192f0a0-0000-7000-8000-00000000000c"), "Clínica Prueba Este");

    private static final TestData.Clinic SUSPENDED =
            new TestData.Clinic(UUID.fromString("0192f0a0-0000-7000-8000-00000000000d"), "Clínica Prueba Oeste");

    private static final List<String> TABLES = List.of("tenancy_test_record", "location");

    /** Invented, as if an exception message carried personal data: it must never reach the logs. */
    private static final String PERSONAL_DATA = "Ana Ejemplo, 600 000 000";

    @Autowired
    private ClinicBatch batch;

    @Autowired
    private ActiveClinicIds activeClinics;

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private LocationRepository locations;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
        TenancyTestDatabase.insertClinic(CLINIC_C, "ACTIVE");
        TenancyTestDatabase.insertClinic(SUSPENDED, "SUSPENDED");
    }

    @AfterEach
    void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(TABLES);
        TenancyTestDatabase.deleteClinicAnd(CLINIC_C.id(), TABLES);
        TenancyTestDatabase.deleteClinicAnd(SUSPENDED.id(), TABLES);
    }

    @Test
    void activeClinicsAreListedInIdOrderWithoutTheSuspendedOnes() {
        List<UUID> ids = activeClinics.activeClinicIds();

        assertThat(ids).contains(CLINIC_A, CLINIC_B, CLINIC_C.id()).doesNotContain(SUSPENDED.id());
        assertThat(ids).isSorted();
    }

    @Test
    void activeClinicsCannotBeListedWithAnActiveClinic() {
        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, activeClinics::activeClinicIds))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyActiveClinicRunsWithItsClinicActiveInATransactionOfItsOwn() {
        List<UUID> seen = Collections.synchronizedList(new ArrayList<>());

        Result result = batch.forEachActiveClinic("test-job", clinicId -> {
            assertThat(TenantContext.current()).isEqualTo(clinicId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            seen.add(clinicId);
            records.save(new TenancyTestRecord("batch"));
        });

        assertThat(seen).contains(CLINIC_A, CLINIC_B, CLINIC_C.id()).doesNotContain(SUSPENDED.id());
        assertThat(result.succeeded()).containsExactlyElementsOf(seen);
        assertThat(result.failed()).isEmpty();
        assertThat(storedPerClinic()).containsExactly(1, 1, 1, 0);
    }

    @Test
    void aFailureInOneClinicIsLoggedWithoutItsMessageAndTheOthersGoOn(CapturedOutput output) {
        Result result = batch.forEachActiveClinic("test-job", clinicId -> {
            records.save(new TenancyTestRecord("batch"));
            if (clinicId.equals(CLINIC_B)) {
                throw new IllegalArgumentException(PERSONAL_DATA);
            }
        });

        assertThat(result.job()).isEqualTo("test-job");
        assertThat(result.succeeded()).contains(CLINIC_A, CLINIC_C.id()).doesNotContain(CLINIC_B, SUSPENDED.id());
        assertThat(result.failed()).containsExactly(new Failure(CLINIC_B, IllegalArgumentException.class.getName()));
        assertThat(result.failedClinicIds()).containsExactly(CLINIC_B);
        // B's work is rolled back; A's and C's are committed, each in its own transaction.
        assertThat(storedPerClinic()).containsExactly(1, 0, 1, 0);

        assertThat(output)
                .contains("Job test-job failed for clinic " + CLINIC_B + ": java.lang.IllegalArgumentException")
                .contains("1 failed: [" + CLINIC_B + "]")
                .doesNotContain(PERSONAL_DATA);
    }

    @Test
    void aFailureAtCommitOnlyRollsBackThatClinic() {
        Result result = batch.forEachActiveClinic("test-job", clinicId -> {
            records.save(new TenancyTestRecord("batch"));
            if (clinicId.equals(CLINIC_B)) {
                // Too long for the column: fails when the transaction flushes, after the task returned.
                records.save(new TenancyTestRecord("x".repeat(101)));
            }
        });

        assertThat(result.failedClinicIds()).containsExactly(CLINIC_B);
        assertThat(storedPerClinic()).containsExactly(1, 0, 1, 0);
    }

    @Test
    void anErrorStopsTheProcess() {
        assertThatThrownBy(() -> batch.forEachActiveClinic("test-job", clinicId -> {
                    records.save(new TenancyTestRecord("batch"));
                    if (clinicId.equals(CLINIC_B)) {
                        throw new SimulatedError();
                    }
                }))
                .isInstanceOf(SimulatedError.class);

        // A was done before B; C, after B, never ran.
        assertThat(storedPerClinic()).containsExactly(1, 0, 0, 0);
    }

    /** Wrapped in a {@code RuntimeException}, as {@code CompletableFuture.join()} would do, an error still stops it. */
    @Test
    void aWrappedErrorStopsTheProcess() {
        assertThatThrownBy(() -> batch.forEachActiveClinic("test-job", clinicId -> {
                    records.save(new TenancyTestRecord("batch"));
                    if (clinicId.equals(CLINIC_B)) {
                        throw new CompletionException(new SimulatedError());
                    }
                }))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SimulatedError.class);

        assertThat(storedPerClinic()).containsExactly(1, 0, 0, 0);
    }

    @Test
    void aBatchCannotStartWithAnActiveClinicOrInsideATransaction() {
        AtomicInteger runs = new AtomicInteger();
        ClinicBatch.ClinicTask task = clinicId -> runs.incrementAndGet();

        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, () -> batch.forEachActiveClinic("test-job", task)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> transaction.executeWithoutResult(status -> batch.forEachActiveClinic("test-job", task)))
                .isInstanceOf(IllegalStateException.class);
        // SUPPORTS opens no transaction, but its entity manager would be shared by every clinic.
        assertThatThrownBy(() -> supports().executeWithoutResult(status -> batch.forEachActiveClinic("test-job", task)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runs).hasValue(0);
    }

    /** There is no user in a process without one: its changes are recorded as the system. */
    @Test
    void changesMadeByABatchAreAuditedAsTheSystem() throws SQLException {
        AtomicReference<UUID> created = new AtomicReference<>();

        batch.forEachActiveClinic("test-job", clinicId -> {
            if (clinicId.equals(CLINIC_A)) {
                created.set(
                        locations.save(new Location("Gabinete del job", null)).getId());
            }
        });

        List<StoredEntry> entries = AuditLogDatabase.entries(CLINIC_A, created.get());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("CREATE");
            assertThat(entry.actorType()).isEqualTo("SYSTEM");
            assertThat(entry.actorId()).isNull();
        });
    }

    private TransactionTemplate supports() {
        TransactionTemplate supports = new TransactionTemplate(transaction.getTransactionManager());
        supports.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        return supports;
    }

    /** Records stored for A, B, C and the suspended clinic, in that order. */
    private static List<Integer> storedPerClinic() {
        List<Integer> counts = new ArrayList<>();
        for (UUID clinic : List.of(CLINIC_A, CLINIC_B, CLINIC_C.id(), SUSPENDED.id())) {
            try {
                counts.add(TenancyTestDatabase.countStored(clinic));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return counts;
    }

    /** Stands for an {@code OutOfMemoryError} or any other {@link Error}. */
    private static final class SimulatedError extends Error {}
}
