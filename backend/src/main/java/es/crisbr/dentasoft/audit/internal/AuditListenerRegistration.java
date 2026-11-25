package es.crisbr.dentasoft.audit.internal;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.stereotype.Component;

/**
 * Adds {@link AuditChangeListener} to the main persistence unit when the application starts. The platform unit does
 * not get it: the platform records its own actions ({@code PlatformAuditRecord}).
 *
 * <p>Appended through the registry, not through an {@code Integrator}, so the tenancy module's integrator list ({@code
 * TenancyHibernateSettings}), which installs the write guard, stays untouched.
 */
@Component
class AuditListenerRegistration {

    AuditListenerRegistration(EntityManagerFactory entityManagerFactory, AuditLogWriter writer) {
        EventListenerRegistry registry = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        AuditChangeListener listener = new AuditChangeListener(writer);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
    }
}
