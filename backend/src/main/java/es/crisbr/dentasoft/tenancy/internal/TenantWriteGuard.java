package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.tenancy.NoActiveClinicException;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import java.util.UUID;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.integrator.spi.Integrator;

/**
 * Stops every insert, update or delete of a {@link TenantScopedEntity} unless a clinic is active and the session was
 * opened for that same clinic. Without it, a session opened without a clinic would write rows with
 * {@link ActiveClinicResolver#NO_CLINIC}, and a session opened before {@code runAs} would write them for the wrong
 * one.
 */
final class TenantWriteGuard implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        check(event.getEntity(), event.getSession());
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        check(event.getEntity(), event.getSession());
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        check(event.getEntity(), event.getSession());
        return false;
    }

    private static void check(Object entity, SharedSessionContractImplementor session) {
        if (!(entity instanceof TenantScopedEntity)) {
            return;
        }
        if (!TenantContext.isBound()) {
            throw new NoActiveClinicException();
        }
        UUID activeClinic = TenantContext.current();
        if (!activeClinic.equals(session.getTenantIdentifierValue())) {
            throw new IllegalStateException(
                    "The session was opened for another clinic: bind the clinic before starting the transaction");
        }
    }

    /** Adds the guard to every session factory, before the other listeners of each event. */
    static final class Registration implements Integrator {

        @Override
        public void integrate(
                Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory) {
            TenantWriteGuard guard = new TenantWriteGuard();
            var registry = sessionFactory.getEventListenerRegistry();
            registry.prependListeners(EventType.PRE_INSERT, guard);
            registry.prependListeners(EventType.PRE_UPDATE, guard);
            registry.prependListeners(EventType.PRE_DELETE, guard);
        }
    }
}
