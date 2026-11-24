package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clinic isolation in Hibernate: the active clinic for every session ({@link ActiveClinicResolver}), the write guard
 * ({@link TenantWriteGuard}) and the clinic passed to PostgreSQL in each transaction ({@link TenantAwareJpaDialect}).
 * The first two are set explicitly ({@link TenancyHibernateSettings}) instead of relying on Hibernate finding a
 * resolver bean on its own, which it silently skips when there is none or more than one.
 */
@Configuration(proxyBeanMethods = false)
class TenancyJpaConfiguration {

    @Bean
    HibernatePropertiesCustomizer tenancyHibernateProperties() {
        return properties -> properties.putAll(TenancyHibernateSettings.properties());
    }

    /** Static, as every bean post-processor, so it is created before the beans it processes. */
    @Bean
    static TenantAwareJpaDialectPostProcessor tenantAwareJpaDialectPostProcessor() {
        return new TenantAwareJpaDialectPostProcessor();
    }
}
