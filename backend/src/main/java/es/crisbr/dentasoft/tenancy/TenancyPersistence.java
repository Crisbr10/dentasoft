package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.tenancy.internal.TenancyHibernateSettings;
import java.util.Map;

/**
 * What a module needs to declare a persistence unit of its own (only the platform module does, ADR 0011).
 *
 * <p>Every entity manager factory gets the {@code TenantAwareJpaDialect}, which refuses to begin a transaction unless
 * the session was opened for the active clinic (or for none, when no clinic is active). A unit built without these
 * settings would have sessions without a clinic, and every transaction on it would fail. With them, its sessions
 * follow the active clinic like the main unit's, and the write guard rejects any {@link TenantScopedEntity} written
 * through it without an active clinic.
 */
public final class TenancyPersistence {

    private TenancyPersistence() {}

    /** The Hibernate properties to add to the unit: a new, mutable map each time. */
    public static Map<String, Object> hibernateProperties() {
        return TenancyHibernateSettings.properties();
    }
}
