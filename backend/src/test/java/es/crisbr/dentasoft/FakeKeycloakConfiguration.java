package es.crisbr.dentasoft;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Publishes the shared {@link FakeKeycloak} as a bean, so tests can inject it to set up users and check calls, and
 * its address as {@code dentasoft.test.keycloak-url}, which {@link WithTestProperties} uses as the application's
 * {@code dentasoft.keycloak-admin.base-url}. The server is shared by every test of the run: tests that use it call
 * {@link FakeKeycloak#reset()} before each test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeKeycloakConfiguration {

    public static final String PROPERTY = "dentasoft.test.keycloak-url";

    @Bean
    static DynamicPropertyRegistrar fakeKeycloakUrl() {
        return registry -> registry.add(PROPERTY, () -> FakeKeycloak.shared().baseUrl());
    }

    /** Not closed with the context: other cached contexts still use it. */
    @Bean(destroyMethod = "")
    FakeKeycloak fakeKeycloak() {
        return FakeKeycloak.shared();
    }
}
