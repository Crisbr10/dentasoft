package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.tenancy.TenancyTestDatabase.countStored;
import static es.crisbr.dentasoft.tenancy.TenancyTestDatabase.stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase.StoredRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hibernate.PropertyValueException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clinic isolation in JPA through {@link TenantScopedEntity}, on the test-only table {@code tenancy_test_record}.
 *
 * <p>Every test goes through the real path: the clinic is bound with {@link TenantContext} and the transaction passes
 * it to PostgreSQL. Only the two tests of Hibernate's own filter override it, with
 * {@link #simulateRlsLetsClinicThrough}.
 */
@IntegrationTest
class TenantScopedEntityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAndRecords();
    }

    @Test
    void insertTakesTheActiveClinic() throws SQLException {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));

        assertThat(record.getTenantId()).isEqualTo(CLINIC_A);
        assertThat(stored(CLINIC_A, record.getId())).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    @Test
    void baseEntityGetsAVersion7IdAuditColumnsAndAVersion() {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));

        assertThat(record.getId().version()).isEqualTo(7);
        assertThat(record.getVersion()).isZero();
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedAt()).isEqualTo(record.getCreatedAt());
        // No users until M02.
        assertThat(record.getCreatedBy()).isNull();
        assertThat(record.getUpdatedBy()).isNull();
    }

    /** With a {@code Long} version, {@code save()} persists a new entity (same instance) instead of merging a copy. */
    @Test
    void newEntitiesArePersistedNotMerged() {
        var information = JpaEntityInformationSupport.getEntityInformation(TenancyTestRecord.class, entityManager);
        TenancyTestRecord record = new TenancyTestRecord("north");
        assertThat(information.isNew(record)).isTrue();

        TenancyTestRecord saved = asClinic(CLINIC_A, () -> records.save(record));

        assertThat(saved).isSameAs(record);
        assertThat(information.isNew(saved)).isFalse();
    }

    @Test
    void insertWithoutActiveClinicFails() throws SQLException {
        Throwable thrown =
                catchThrowable(() -> inTransaction(() -> records.saveAndFlush(new TenancyTestRecord("north"))));

        assertThat(causes(thrown)).anyMatch(NoActiveClinicException.class::isInstance);
        assertThat(countStored(CLINIC_A)).isZero();
    }

    @Test
    void insertWithAnotherClinicAssignedFails() throws SQLException {
        TenancyTestRecord record = new TenancyTestRecord("north");
        ReflectionTestUtils.setField(record, "tenantId", CLINIC_B);

        Throwable thrown = catchThrowable(() -> asClinic(CLINIC_A, () -> records.saveAndFlush(record)));

        assertThat(causes(thrown)).anyMatch(PropertyValueException.class::isInstance);
        assertThat(countStored(CLINIC_A)).isZero();
        assertThat(countStored(CLINIC_B)).isZero();
    }

    @Test
    void clinicOfAStoredEntityCannotChange() throws SQLException {
        UUID id = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")))
                .getId();

        asClinic(CLINIC_A, () -> {
            TenancyTestRecord record = records.findById(id).orElseThrow();
            ReflectionTestUtils.setField(record, "tenantId", CLINIC_B);
            record.setLabel("north, renamed");
            return records.saveAndFlush(record);
        });

        assertThat(stored(CLINIC_A, id)).hasValue(new StoredRecord(CLINIC_A, "north, renamed"));
        assertThat(countStored(CLINIC_B)).isZero();
    }

    @Test
    void queriesOnlyReturnTheActiveClinic() {
        seed();

        assertThat(asClinic(CLINIC_A, this::allLabels)).containsExactly("north");
        assertThat(asClinic(CLINIC_B, this::allLabels)).containsExactly("south");
    }

    /** Even if PostgreSQL let clinic B's rows through, with clinic A active no JPA query or lookup returns them. */
    @Test
    void hibernateFiltersByTheActiveClinicOnItsOwn() {
        UUID southId = seed().south();

        asClinic(CLINIC_A, () -> {
            simulateRlsLetsClinicThrough(CLINIC_B);
            assertThat(records.findAll()).isEmpty();
            assertThat(records.findById(southId)).isEmpty();
            assertThat(entityManager.find(TenancyTestRecord.class, southId)).isNull();
            assertThat(records.findByLabel("south")).isEmpty();
            assertThat(records.findByLabelPrefix("so")).isEmpty();
            assertThat(records.count()).isZero();
            return null;
        });
        assertThat(asClinic(CLINIC_B, this::allLabels)).containsExactly("south");
    }

    /**
     * Without an active clinic, sessions use the {@code NO_CLINIC} marker: even if PostgreSQL let clinic A's rows
     * through, no query would return any row, and a bulk update would change none.
     */
    @Test
    void withoutActiveClinicQueriesReturnNoRows() throws SQLException {
        UUID northId = seed().north();

        inTransaction(() -> {
            simulateRlsLetsClinicThrough(CLINIC_A);
            assertThat(records.findAll()).isEmpty();
            assertThat(records.findById(northId)).isEmpty();
            assertThat(entityManager.find(TenancyTestRecord.class, northId)).isNull();
            assertThat(records.findByLabel("north")).isEmpty();
            assertThat(records.findByLabelPrefix("no")).isEmpty();
            assertThat(records.count()).isZero();
            assertThat(records.relabelAll("changed")).isZero();
            return null;
        });

        assertThat(stored(CLINIC_A, northId)).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    @Test
    void withoutActiveClinicAStoredEntityCannotBeUpdatedOrDeleted() throws SQLException {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));
        record.setLabel("north, renamed");

        // The filtered load finds nothing, so the detached entity looks deleted.
        assertThat(catchThrowable(() -> inTransaction(() -> records.saveAndFlush(record))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        inTransaction(() -> {
            records.deleteById(record.getId());
            records.delete(record);
            return null;
        });

        assertThat(stored(CLINIC_A, record.getId())).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    /** An EntityManager keeps the clinic it was opened with: writing through it with another clinic active fails. */
    @Test
    void writingThroughASessionOpenedForAnotherClinicFails(@Autowired EntityManagerFactory factory)
            throws SQLException {
        try (EntityManager openedForA = TenantContext.callAs(CLINIC_A, factory::createEntityManager)) {
            Throwable thrown = catchThrowable(() -> TenantContext.runAs(CLINIC_B, () -> {
                openedForA.getTransaction().begin();
                try {
                    openedForA.persist(new TenancyTestRecord("south"));
                    openedForA.flush();
                } finally {
                    openedForA.getTransaction().rollback();
                }
            }));

            assertThat(causes(thrown))
                    .anyMatch(cause -> cause instanceof IllegalStateException
                            && String.valueOf(cause.getMessage()).contains("opened for another clinic"));
        }
        assertThat(countStored(CLINIC_A)).isZero();
        assertThat(countStored(CLINIC_B)).isZero();
    }

    /**
     * With open-in-view, the session would open when the request starts, before any {@code runAs}, and keep
     * {@code NO_CLINIC} for the whole request. {@code runAs} cannot see it: it only knows about transactions.
     */
    @Test
    void sessionsAreNotOpenedForTheWholeRequest(@Autowired ApplicationContext context) {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class))
                .isEmpty();
    }

    /** The session took its clinic when the transaction began: binding one afterwards would not reach it. */
    @Test
    void clinicCannotBeBoundInsideATransactionStartedWithoutOne() {
        inTransaction(() -> {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TenantContext.runAs(CLINIC_A, () -> {}))
                    .withMessageContaining("before the transaction");
            return null;
        });
    }

    private Seeded seed() {
        UUID north = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")))
                .getId();
        UUID south = asClinic(CLINIC_B, () -> records.save(new TenancyTestRecord("south")))
                .getId();
        return new Seeded(north, south);
    }

    private List<String> allLabels() {
        return records.findAll().stream().map(TenancyTestRecord::getLabel).toList();
    }

    /** {@code work} with {@code clinic} active, in a transaction, which passes it to PostgreSQL. */
    private <T> T asClinic(UUID clinic, Supplier<T> work) {
        return TenantContext.callAs(clinic, () -> inTransaction(work));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    /** Only {@code hibernateFiltersByTheActiveClinicOnItsOwn} and {@code withoutActiveClinicQueriesReturnNoRows}. */
    private void simulateRlsLetsClinicThrough(UUID clinic) {
        HibernateIsolationChecks.simulateRlsLetsClinicThrough(jdbc, "tenancy_test_record", clinic);
    }

    private static Stream<Throwable> causes(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause);
    }

    private record Seeded(UUID north, UUID south) {}
}
