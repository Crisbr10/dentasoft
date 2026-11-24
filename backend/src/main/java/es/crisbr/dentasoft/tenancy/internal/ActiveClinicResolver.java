package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.tenancy.TenantContext;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Gives every Hibernate session the active clinic of {@link TenantContext}. Hibernate asks when a session opens: at
 * the start of each transaction, and also at startup, when Spring Data checks the query methods of the repositories.
 *
 * <p>Without an active clinic it returns {@link #NO_CLINIC}, which is never a clinic. Sessions still open, so data
 * that belongs to no clinic (the {@code clinic} table itself, platform administration) stays reachable, but:
 *
 * <ul>
 *   <li>every query on a {@code TenantScopedEntity} filters by {@code tenant_id = NO_CLINIC} and finds nothing;
 *   <li>every insert, update or delete of one fails with {@code NoActiveClinicException} ({@link TenantWriteGuard}).
 * </ul>
 *
 * <p>No clinic is ever "root": Hibernate would drop the clinic filter for it.
 */
public final class ActiveClinicResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** The clinic of sessions opened without one: the nil UUID, never generated for a clinic (they are UUID v7). */
    public static final UUID NO_CLINIC = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.isBound() ? TenantContext.current() : NO_CLINIC;
    }

    /** Only used by {@code SessionFactory.getCurrentSession()}, which Spring does not use with JPA. */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID clinicId) {
        return false;
    }
}
