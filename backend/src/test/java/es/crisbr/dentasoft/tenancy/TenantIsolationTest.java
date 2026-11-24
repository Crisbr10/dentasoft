package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Template for the clinic isolation test that every module with clinic data must have (AGENTS.md): clinic A must not
 * read, change or delete the data of clinic B. {@link TenantIsolationCoverageTests} fails while an entity that extends
 * {@link TenantScopedEntity} has no subclass of this template.
 *
 * <p>It creates clinics A and B ({@link TestData}), stores one entity for each through the module's repository, and
 * checks the two layers of isolation <strong>separately</strong>, because with only one removed the other would still
 * protect the data and a single end-to-end test would pass:
 *
 * <ul>
 *   <li><strong>Row Level Security</strong>: with clinic A active, native SQL, which Hibernate does not filter, does
 *       not see clinic B's row, cannot change or delete it, and cannot write rows into clinic B. It fails if the
 *       {@code tenant_isolation} policy is removed or Row Level Security is disabled.
 *   <li><strong>Hibernate</strong> ({@code @TenantId}): with clinic A active and PostgreSQL told to let clinic B's rows
 *       through, no JPA lookup, query, change or delete reaches clinic B's entity. It fails if the entity maps its
 *       {@code tenant_id} without {@code @TenantId}. If {@code @TenantId} is removed from {@link TenantScopedEntity}
 *       itself, nothing gives entities their clinic: storing them fails, and so does every test, before any check.
 * </ul>
 *
 * <p>After every attempt to change or delete, the row is read back as the owner, {@code dentasoft_owner}, and compared
 * column by column: an attempt that does not fail is not enough. {@link TenantIsolationTestMutationTests} proves that
 * each check fails when its layer is broken.
 *
 * <p>Usage, in the module's test package, with invented data from {@link TestData}:
 *
 * <pre>{@code
 * class PatientIsolationTests extends TenantIsolationTest<Patient> {
 *
 *     @Autowired
 *     private PatientRepository patients;
 *
 *     @Override
 *     protected JpaRepository<Patient, UUID> repository() {
 *         return patients;
 *     }
 *
 *     @Override
 *     protected String table() {
 *         return "patient";
 *     }
 *
 *     @Override
 *     protected Patient newEntity(int n) {
 *         TestData.Person person = TestData.person(n);
 *         return new Patient(person.givenName(), person.familyName());
 *     }
 *
 *     @Override
 *     protected void modify(Patient patient) {
 *         patient.rename(patient.givenName() + " (changed)");
 *     }
 * }
 * }</pre>
 *
 * <p>If {@link #newEntity} also stores rows in other tables (a parent the entity needs), list them in
 * {@link #tablesToClean} so they are removed after each test.
 *
 * @param <E> the module's entity
 */
@IntegrationTest
public abstract class TenantIsolationTest<E extends TenantScopedEntity> {

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    private RowLevelSecurityIsolationChecks rowLevelSecurity;
    private HibernateIsolationChecks<E> hibernate;

    /** The module's repository of {@code E}. */
    protected abstract JpaRepository<E, UUID> repository();

    /** The table of {@code E}, in lower case, as in its migration. */
    protected abstract String table();

    /**
     * A new, unsaved entity with invented data from {@link TestData}; {@code n} is 0 for clinic A's entity and 1 for
     * clinic B's. It is saved with that clinic active, inside a transaction.
     */
    protected abstract E newEntity(int n);

    /** Changes {@code entity} so that saving it would change at least one column. */
    protected abstract void modify(E entity);

    /** Tables to empty for clinics A and B after each test, in this order. By default, {@link #table()}. */
    protected List<String> tablesToClean() {
        return List.of(table());
    }

    @BeforeEach
    protected final void storeOneEntityForEachClinic() throws SQLException {
        TenancyTestDatabase.insertClinics();
        IsolationFixture seeding = new IsolationFixture(table(), null, null, transaction);
        UUID own = store(seeding, IsolationFixture.CLINIC_A, 0);
        UUID other = store(seeding, IsolationFixture.CLINIC_B, 1);
        IsolationFixture fixture = new IsolationFixture(table(), own, other, transaction);
        rowLevelSecurity = new RowLevelSecurityIsolationChecks(fixture, jdbc);
        hibernate =
                new HibernateIsolationChecks<>(fixture, entityType(), repository(), this::modify, entityManager, jdbc);
    }

    @AfterEach
    protected final void deleteBothClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(tablesToClean());
    }

    @Test
    protected final void rowLevelSecurityHidesClinicBsRows() {
        rowLevelSecurity.hidesTheOtherClinic();
    }

    @Test
    protected final void rowLevelSecurityBlocksUpdatesOfClinicBsRows() {
        rowLevelSecurity.blocksUpdatesOfTheOtherClinic();
    }

    @Test
    protected final void rowLevelSecurityBlocksDeletesOfClinicBsRows() {
        rowLevelSecurity.blocksDeletesOfTheOtherClinic();
    }

    @Test
    protected final void rowLevelSecurityBlocksWritesIntoClinicB() {
        rowLevelSecurity.blocksWritesIntoTheOtherClinic();
    }

    @Test
    protected final void hibernateHidesClinicBsEntities() {
        hibernate.hidesTheOtherClinic();
    }

    @Test
    protected final void hibernateBlocksUpdatesOfClinicBsEntities() {
        hibernate.blocksUpdatesOfTheOtherClinic();
    }

    @Test
    protected final void hibernateBlocksDeletesOfClinicBsEntities() {
        hibernate.blocksDeletesOfTheOtherClinic();
    }

    /** Fails with the likely reason: without {@code @TenantId}, nothing gives the entity its clinic. */
    private UUID store(IsolationFixture seeding, UUID clinic, int n) {
        try {
            return seeding.asClinic(clinic, () -> repository().saveAndFlush(newEntity(n)))
                    .getId();
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "could not store an entity of %s for clinic %s; does it still extend TenantScopedEntity, whose"
                                    .formatted(table(), clinic)
                            + " @TenantId gives it the active clinic?",
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<E> entityType() {
        Class<?> type = ResolvableType.forClass(getClass())
                .as(TenantIsolationTest.class)
                .resolveGeneric(0);
        if (type == null) {
            throw new IllegalStateException(getClass().getName() + " must extend TenantIsolationTest<YourEntity>");
        }
        return (Class<E>) type;
    }
}
