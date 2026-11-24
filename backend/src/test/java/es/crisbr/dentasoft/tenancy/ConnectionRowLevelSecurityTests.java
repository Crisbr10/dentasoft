package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.tenancy.TenancyTestDatabase.countStored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row Level Security with the clinic each transaction passes to PostgreSQL ({@code app.tenant_id}), seen through
 * native SQL, which Hibernate does not filter: whatever these queries return is what PostgreSQL lets through.
 *
 * <p>Reading a clinic's data requires a transaction. Outside one, tables with Row Level Security return no rows.
 */
@IntegrationTest
class ConnectionRowLevelSecurityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        inTransaction(CLINIC_A, required(), () -> records.save(new TenancyTestRecord("north")));
        inTransaction(CLINIC_B, required(), () -> records.save(new TenancyTestRecord("south")));
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAndRecords();
    }

    @Test
    void nativeQueryWithoutFilterOnlyReturnsTheActiveClinic() {
        assertThat(inTransaction(CLINIC_A, required(), this::nativeLabels)).containsExactly("north");
        assertThat(inTransaction(CLINIC_B, required(), this::nativeLabels)).containsExactly("south");
        assertThat(inTransaction(CLINIC_A, required(), this::jdbcLabels)).containsExactly("north");
        assertThat(inTransaction(CLINIC_B, required(), this::jdbcLabels)).containsExactly("south");
    }

    @Test
    void withoutActiveClinicTablesWithRowLevelSecurityReturnNoRows() {
        assertThat(inTransaction(null, required(), this::nativeLabels)).isEmpty();
        assertThat(inTransaction(null, required(), this::jdbcLabels)).isEmpty();
        assertThat(inTransaction(null, required(), this::databaseClinic)).isNull();
    }

    @Test
    void readOnlyTransactionsUseTheActiveClinic() {
        TransactionTemplate readOnly = required();
        readOnly.setReadOnly(true);

        assertThat(inTransaction(CLINIC_A, readOnly, this::nativeLabels)).containsExactly("north");
        assertThat(inTransaction(CLINIC_A, readOnly, this::databaseClinic)).isEqualTo(CLINIC_A.toString());
    }

    /** {@code JdbcClient} inside the transaction runs on the session's connection, with its clinic. */
    @Test
    void jdbcClientInsideTheTransactionUsesTheSessionConnection() {
        inTransaction(CLINIC_A, required(), () -> {
            assertThat(jdbcBackendPid()).isEqualTo(nativeBackendPid());
            assertThat(jdbcLabels()).containsExactly("north");
            return null;
        });
    }

    /** A new transaction takes another connection, and passes it the clinic too. */
    @Test
    void requiresNewTransactionsUseTheActiveClinicOnTheirOwnConnection() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        inTransaction(CLINIC_A, required(), () -> {
            int outerPid = jdbcBackendPid();
            requiresNew.executeWithoutResult(status -> {
                assertThat(jdbcBackendPid()).isNotEqualTo(outerPid);
                assertThat(databaseClinic()).isEqualTo(CLINIC_A.toString());
                assertThat(nativeLabels()).containsExactly("north");
            });
            assertThat(jdbcBackendPid()).isEqualTo(outerPid);
            assertThat(databaseClinic()).isEqualTo(CLINIC_A.toString());
            return null;
        });
    }

    @Test
    void aRolledBackTransactionLeavesNothingBehind() throws SQLException {
        Throwable thrown = catchThrowable(() -> inTransaction(CLINIC_A, required(), () -> {
            records.saveAndFlush(new TenancyTestRecord("north, again"));
            throw new IllegalStateException("simulated failure");
        }));

        assertThat(thrown).hasMessage("simulated failure");
        assertThat(countStored(CLINIC_A)).isEqualTo(1);
        assertThat(PooledConnections.inspectAll(dataSource))
                .allMatch(PooledConnections.IdleConnection::hasNoClinic)
                .allMatch(connection -> connection.visibleRows() == 0);
    }

    /**
     * Without a transaction nothing passes the clinic to PostgreSQL, even with one active: native SQL and
     * {@code JdbcClient} see no rows. Spring Data repository methods are not affected: each one opens its own
     * transaction.
     */
    @Test
    void codeWithoutTransactionSeesNoRows() {
        TransactionTemplate supports = new TransactionTemplate(transactionManager);
        supports.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        TransactionTemplate notSupported = new TransactionTemplate(transactionManager);
        notSupported.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        for (TransactionTemplate withoutTransaction : List.of(supports, notSupported)) {
            assertThat(inTransaction(CLINIC_A, withoutTransaction, this::nativeLabels))
                    .isEmpty();
            assertThat(inTransaction(CLINIC_A, withoutTransaction, this::jdbcLabels))
                    .isEmpty();
        }
        assertThat(TenantContext.callAs(CLINIC_A, this::jdbcLabels)).isEmpty();
        assertThat(TenantContext.callAs(CLINIC_A, () -> records.findAll()))
                .extracting(TenancyTestRecord::getLabel)
                .containsExactly("north");
    }

    /**
     * A transaction that would join a session opened for another clinic, or without one, does not start: PostgreSQL
     * would use the session's clinic, not the active one.
     */
    @Test
    void aTransactionOnASessionOpenedForAnotherClinicDoesNotStart(@Autowired EntityManagerFactory factory) {
        assertThat(causes(catchThrowable(() -> joiningSessionOpenedFor(factory, CLINIC_A, CLINIC_B))))
                .anyMatch(ConnectionRowLevelSecurityTests::isClinicMismatch);
        assertThat(causes(catchThrowable(() -> joiningSessionOpenedFor(factory, CLINIC_A, null))))
                .anyMatch(ConnectionRowLevelSecurityTests::isClinicMismatch);
        assertThat(causes(catchThrowable(() -> joiningSessionOpenedFor(factory, null, CLINIC_A))))
                .anyMatch(ConnectionRowLevelSecurityTests::isClinicMismatch);
        assertThat(catchThrowable(() -> joiningSessionOpenedFor(factory, CLINIC_A, CLINIC_B)))
                .isInstanceOf(CannotCreateTransactionException.class);
    }

    /**
     * Starts a transaction with {@code activeClinic} on a session opened for {@code sessionClinic} and bound
     * beforehand, as open-in-view would do; {@code null} is no clinic.
     */
    private void joiningSessionOpenedFor(EntityManagerFactory factory, UUID sessionClinic, UUID activeClinic) {
        EntityManager session = withClinic(sessionClinic, factory::createEntityManager);
        TransactionSynchronizationManager.bindResource(factory, new EntityManagerHolder(session));
        try {
            inTransaction(activeClinic, required(), this::nativeLabels);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(factory);
            session.close();
        }
    }

    private static boolean isClinicMismatch(Throwable cause) {
        return cause instanceof IllegalStateException
                && String.valueOf(cause.getMessage()).contains("opened for another clinic");
    }

    private TransactionTemplate required() {
        return new TransactionTemplate(transactionManager);
    }

    /** {@code work} with {@code clinic} active ({@code null}: none) in {@code template}'s transaction. */
    private <T> T inTransaction(UUID clinic, TransactionTemplate template, Supplier<T> work) {
        return withClinic(clinic, () -> template.execute(status -> work.get()));
    }

    private static <T> T withClinic(UUID clinic, Supplier<T> work) {
        return clinic == null ? work.get() : TenantContext.callAs(clinic, work::get);
    }

    @SuppressWarnings("unchecked")
    private List<String> nativeLabels() {
        return entityManager
                .createNativeQuery("SELECT label FROM tenancy_test_record ORDER BY label", String.class)
                .getResultList();
    }

    private List<String> jdbcLabels() {
        return jdbc.sql("SELECT label FROM tenancy_test_record ORDER BY label")
                .query(String.class)
                .list();
    }

    /** {@code app.tenant_id} as PostgreSQL sees it now; {@code null} or empty when no clinic is set. */
    private String databaseClinic() {
        String clinic = jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                .query(String.class)
                .single();
        return clinic == null || clinic.isEmpty() ? null : clinic;
    }

    private int jdbcBackendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private int nativeBackendPid() {
        return ((Number) entityManager
                        .createNativeQuery("SELECT pg_backend_pid()")
                        .getSingleResult())
                .intValue();
    }

    private static Stream<Throwable> causes(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause);
    }
}
