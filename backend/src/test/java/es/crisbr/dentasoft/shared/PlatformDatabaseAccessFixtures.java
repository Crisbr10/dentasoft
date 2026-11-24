package es.crisbr.dentasoft.shared;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classes outside the platform module that ask for the platform database in every way {@code
 * PlatformDatabaseAccessTests} must catch. Not Spring beans: only that test imports them.
 */
public final class PlatformDatabaseAccessFixtures {

    public static final Class<?>[] CLASSES = {
        TransactionalByName.class, QualifiedConstructor.class, PlatformEntityManager.class, Harmless.class
    };

    /** One per platform annotation below, none for {@link Harmless}. */
    public static final int VIOLATIONS = 5;

    private PlatformDatabaseAccessFixtures() {}

    @Transactional("platformTransactionManager")
    static class TransactionalByName {

        @Transactional(transactionManager = "platformTransactionManager")
        void method() {}
    }

    static class QualifiedConstructor {

        QualifiedConstructor(@Qualifier("platformDataSource") DataSource dataSource) {}
    }

    static class PlatformEntityManager {

        @PersistenceContext(unitName = "platform")
        private EntityManager entityManager;

        @PersistenceUnit(unitName = "platform")
        private EntityManagerFactory entityManagerFactory;
    }

    /** Uses the main unit: must not be reported. */
    static class Harmless {

        @PersistenceContext
        private EntityManager entityManager;

        @Transactional(readOnly = true)
        void method() {}
    }
}
