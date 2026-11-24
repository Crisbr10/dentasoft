package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Installs {@link TenantAwareJpaDialect} on the entity manager factory, and refuses to start the application with any
 * transaction manager that would not pass the clinic to PostgreSQL: one that is not a {@link JpaTransactionManager}
 * (a JDBC or JTA one, for example), or one that uses another dialect.
 *
 * <p>The dialect has to be set on the factory: the transaction manager copies the factory's dialect when it is
 * initialized, so a dialect set on the transaction manager alone would be replaced. The factory only takes the
 * vendor adapter's dialect when it has none, so it keeps this one.
 *
 * <p>If this ever stopped applying, transactions would run without the clinic in PostgreSQL: the second check makes
 * startup fail instead.
 */
final class TenantAwareJpaDialectPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractEntityManagerFactoryBean factory) {
            factory.setJpaDialect(new TenantAwareJpaDialect());
        }
        return bean;
    }

    /** Every transaction manager must be a {@link JpaTransactionManager} with {@link TenantAwareJpaDialect}. */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof PlatformTransactionManager transactionManager && !passesTheClinic(transactionManager)) {
            String uses = transactionManager instanceof JpaTransactionManager jpa
                    ? "its JPA dialect is " + jpa.getJpaDialect().getClass().getName()
                    : "it is a " + transactionManager.getClass().getName();
            throw new BeanInitializationException(
                    "Transaction manager '" + beanName + "' does not pass the active clinic to PostgreSQL: " + uses);
        }
        return bean;
    }

    private static boolean passesTheClinic(PlatformTransactionManager transactionManager) {
        return transactionManager instanceof JpaTransactionManager jpa
                && jpa.getJpaDialect() instanceof TenantAwareJpaDialect;
    }
}
