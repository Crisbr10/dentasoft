package es.crisbr.dentasoft.tenancy.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;

/**
 * The Hibernate settings every persistence unit of the application needs: the active clinic for every session
 * ({@link ActiveClinicResolver}) and the write guard ({@link TenantWriteGuard}). Published to other modules through
 * {@link es.crisbr.dentasoft.tenancy.TenancyPersistence}.
 *
 * <p>{@code hibernate.integrator_provider} holds a single provider: any other integrator the application needs goes
 * in this list, never in a second customizer, which would silently replace the guard.
 */
public final class TenancyHibernateSettings {

    private TenancyHibernateSettings() {}

    /** A new, mutable map each time, so a caller can add its own settings. */
    public static Map<String, Object> properties() {
        IntegratorProvider integrators = () -> List.of(new TenantWriteGuard.Registration());
        Map<String, Object> properties = new HashMap<>();
        properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new ActiveClinicResolver());
        properties.put(JpaSettings.INTEGRATOR_PROVIDER, integrators);
        return properties;
    }
}
