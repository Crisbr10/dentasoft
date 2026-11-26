package es.crisbr.dentasoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The properties of {@link WithTestEnvironment} without its Redis container, for tests that bring their own Redis
 * (or none). Uses the reserved {@code .test} domain so no real host ends up in the tests. Includes the PostgreSQL of
 * {@link TestPostgresConfiguration}: the application does not start without its database. The Keycloak Admin API is
 * the shared {@link FakeKeycloak} of {@link FakeKeycloakConfiguration}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Import({TestPostgresConfiguration.class, TestJwksConfiguration.class, FakeKeycloakConfiguration.class})
@TestPropertySource(
        properties = {
            "dentasoft.urls.app=" + WithTestEnvironment.APP,
            "dentasoft.urls.api=" + WithTestEnvironment.API,
            "dentasoft.urls.auth=" + WithTestEnvironment.AUTH,
            "dentasoft.rate-limit.key=" + WithTestEnvironment.RATE_LIMIT_KEY,
            // Tokens are checked against the local JWK set of TestJwks, never a real Keycloak.
            "dentasoft.auth.jwk-set-uri=${" + TestJwksConfiguration.PROPERTY + "}",
            // The Admin API calls go to the in-process FakeKeycloak, with its test-only client secret.
            "dentasoft.keycloak-admin.base-url=${" + FakeKeycloakConfiguration.PROPERTY + "}",
            "dentasoft.keycloak-admin.client-secret=" + FakeKeycloak.CLIENT_SECRET,
            // Test-only tables (db/testmigration), numbered from V900 so they always run after the real ones.
            "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration"
        })
public @interface WithTestProperties {}
