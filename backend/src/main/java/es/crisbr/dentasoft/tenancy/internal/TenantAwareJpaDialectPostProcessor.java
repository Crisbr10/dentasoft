package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Installs {@link TenantAwareJpaDialect} on the entity manager factory, and refuses to start the application with a
 * {@link JpaTransactionManager} that uses any other dialect.
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

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JpaTransactionManager transactionManager
                && !(transactionManager.getJpaDialect() instanceof TenantAwareJpaDialect)) {
            throw new BeanInitializationException("Transaction manager '" + beanName
                    + "' does not pass the active clinic to PostgreSQL: its JPA dialect is "
                    + transactionManager.getJpaDialect().getClass().getName());
        }
        return bean;
    }
}
