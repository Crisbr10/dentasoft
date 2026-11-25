package es.crisbr.dentasoft.audit.internal;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerGroup;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Adds {@link AuditChangeListener} to every entity manager factory as soon as it is built, before anything can use it.
 *
 * <p>A post-processor, so it cannot be skipped: post-processors are always created, before the factories, even with
 * lazy initialization, and no audited change can be written by a factory without the listener. The platform unit gets
 * it too, where it finds nothing to record: no platform entity is an {@code AuditedEntity}, as the platform records
 * its own actions ({@code PlatformAuditRecord}).
 *
 * <p>Appended through the registry, not through an {@code Integrator}, so the tenancy module's integrator list ({@code
 * TenancyHibernateSettings}), which installs the write guard, stays untouched.
 */
final class AuditListenerRegistration implements BeanPostProcessor {

    private final AuditChangeListener listener = new AuditChangeListener(new AuditLogWriter());

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof EntityManagerFactory entityManagerFactory) {
            register(entityManagerFactory);
        }
        return bean;
    }

    private void register(EntityManagerFactory entityManagerFactory) {
        EventListenerRegistry registry = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        appendOnce(registry, EventType.POST_INSERT);
        appendOnce(registry, EventType.POST_UPDATE);
        appendOnce(registry, EventType.POST_DELETE);
    }

    /**
     * Spring may hand the same factory over more than once, as it does with the platform's, and Hibernate rejects a
     * second listener of the same class.
     */
    private <T> void appendOnce(EventListenerRegistry registry, EventType<T> type) {
        EventListenerGroup<T> group = registry.getEventListenerGroup(type);
        for (T existing : group.listeners()) {
            if (existing instanceof AuditChangeListener) {
                return;
            }
        }
        group.appendListener(type.baseListenerInterface().cast(listener));
    }
}
