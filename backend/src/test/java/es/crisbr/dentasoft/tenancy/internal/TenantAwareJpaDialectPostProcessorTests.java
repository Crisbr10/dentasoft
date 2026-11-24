package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Without {@link TenantAwareJpaDialect}, transactions would not pass the clinic to PostgreSQL. These tests fail if the
 * post-processor stops being applied, and check that it makes startup fail rather than run without it.
 */
class TenantAwareJpaDialectPostProcessorTests {

    private final TenantAwareJpaDialectPostProcessor postProcessor = new TenantAwareJpaDialectPostProcessor();

    @Test
    void installsTheDialectOnTheEntityManagerFactory() {
        var factory = new LocalContainerEntityManagerFactoryBean();

        postProcessor.postProcessBeforeInitialization(factory, "entityManagerFactory");

        assertThat(factory.getJpaDialect()).isInstanceOf(TenantAwareJpaDialect.class);
    }

    @Test
    void rejectsATransactionManagerWithAnotherDialect() {
        var transactionManager = new JpaTransactionManager();
        transactionManager.setJpaDialect(new HibernateJpaDialect());

        assertThatExceptionOfType(BeanInitializationException.class)
                .isThrownBy(
                        () -> postProcessor.postProcessAfterInitialization(transactionManager, "transactionManager"))
                .withMessageContaining("does not pass the active clinic to PostgreSQL");
    }

    @Test
    void acceptsATransactionManagerWithTheDialect() {
        var transactionManager = new JpaTransactionManager();
        transactionManager.setJpaDialect(new TenantAwareJpaDialect());

        assertThat(postProcessor.postProcessAfterInitialization(transactionManager, "transactionManager"))
                .isSameAs(transactionManager);
    }

    @Nested
    @IntegrationTest
    class InTheApplication {

        @Test
        void transactionsUseTheDialect(
                @Autowired PlatformTransactionManager transactionManager, @Autowired EntityManagerFactory factory) {
            assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
            assertThat(((JpaTransactionManager) transactionManager).getJpaDialect())
                    .isInstanceOf(TenantAwareJpaDialect.class);
            assertThat(((EntityManagerFactoryInfo) factory).getJpaDialect()).isInstanceOf(TenantAwareJpaDialect.class);
        }
    }
}
