package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.UUID;
import org.hibernate.FlushMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * The failure paths of {@link TenantAwareJpaDialect} that a real database does not produce: {@code set_config}
 * failing after the transaction began, and a session opened for another clinic. The real path is covered by
 * {@code ConnectionRowLevelSecurityTests}.
 */
class TenantAwareJpaDialectTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();

    private final TenantAwareJpaDialect dialect = new TenantAwareJpaDialect();
    private final EntityManager entityManager = mock(EntityManager.class);
    private final SessionImplementor session = mock(SessionImplementor.class);
    private final EntityTransaction transaction = mock(EntityTransaction.class);

    @BeforeEach
    void openSessionForClinicA() {
        when(entityManager.unwrap(SessionImplementor.class)).thenReturn(session);
        when(entityManager.getTransaction()).thenReturn(transaction);
        when(session.getTenantIdentifierValue()).thenReturn(CLINIC_A);
        when(session.getHibernateFlushMode()).thenReturn(FlushMode.AUTO);
    }

    /** The transaction that began is rolled back, so it does not go on without the clinic, and the error surfaces. */
    @Test
    void whenSetConfigFailsTheTransactionIsRolledBack() {
        RuntimeException failure = new IllegalStateException("set_config failed");
        doThrow(failure).when(session).doWork(any(Work.class));

        Throwable thrown = catchThrowable(() -> TenantContext.runAs(CLINIC_A, this::begin));

        assertThat(thrown).isSameAs(failure);
        verify(transaction).begin();
        verify(transaction).rollback();
    }

    @Test
    void aFailedRollbackDoesNotHideTheOriginalError() {
        RuntimeException failure = new IllegalStateException("set_config failed");
        RuntimeException rollbackFailure = new IllegalStateException("rollback failed");
        doThrow(failure).when(session).doWork(any(Work.class));
        doThrow(rollbackFailure).when(transaction).rollback();

        Throwable thrown = catchThrowable(() -> TenantContext.runAs(CLINIC_A, this::begin));

        assertThat(thrown).isSameAs(failure).hasSuppressedException(rollbackFailure);
    }

    /** The clinics are compared before anything begins: nothing to roll back, no clinic sent. */
    @Test
    void aSessionOpenedForAnotherClinicNeverBegins() {
        Throwable thrown = catchThrowable(() -> TenantContext.runAs(TestData.CLINIC_B.id(), this::begin));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("opened for another clinic");
        verify(transaction, never()).begin();
        verify(session, never()).doWork(any(Work.class));
    }

    @Test
    void withoutClinicNothingIsSent() {
        when(session.getTenantIdentifierValue()).thenReturn(ActiveClinicResolver.NO_CLINIC);

        begin();

        verify(transaction).begin();
        verify(session, never()).doWork(any(Work.class));
    }

    private void begin() {
        try {
            dialect.beginTransaction(entityManager, new DefaultTransactionDefinition());
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }
}
