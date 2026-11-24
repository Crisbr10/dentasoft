package es.crisbr.dentasoft.tenancy.internal;

import java.util.List;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clinic isolation in Hibernate: the active clinic for every session ({@link ActiveClinicResolver}), the write guard
 * ({@link TenantWriteGuard}) and the clinic passed to PostgreSQL in each transaction ({@link TenantAwareJpaDialect}).
 * The first two are set explicitly instead of relying on Hibernate finding a resolver bean on its own, which it
 * silently skips when there is none or more than one.
 *
 * <p>{@code hibernate.integrator_provider} holds a single provider: any other integrator the application needs goes
 * in this list, never in a second customizer, which would silently replace the guard.
 */
@Configuration(proxyBeanMethods = false)
class TenancyJpaConfiguration {

    @Bean
    HibernatePropertiesCustomizer tenancyHibernateProperties() {
        IntegratorProvider integrators = () -> List.of(new TenantWriteGuard.Registration());
        return properties -> {
            properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new ActiveClinicResolver());
            properties.put(JpaSettings.INTEGRATOR_PROVIDER, integrators);
        };
    }

    /** Static, as every bean post-processor, so it is created before the beans it processes. */
    @Bean
    static TenantAwareJpaDialectPostProcessor tenantAwareJpaDialectPostProcessor() {
        return new TenantAwareJpaDialectPostProcessor();
    }
}
