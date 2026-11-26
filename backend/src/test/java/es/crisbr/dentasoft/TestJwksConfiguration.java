package es.crisbr.dentasoft;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Publishes the address of {@link TestJwks} as {@code dentasoft.test.jwks-uri}, which {@link WithTestProperties} uses
 * as the application's {@code dentasoft.auth.jwk-set-uri}. A test that needs another JWK set registers {@code
 * dentasoft.auth.jwk-set-uri} itself with {@code @DynamicPropertySource}, which wins over {@code @TestPropertySource}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwksConfiguration {

    public static final String PROPERTY = "dentasoft.test.jwks-uri";

    @Bean
    static DynamicPropertyRegistrar testJwksUri() {
        return registry -> registry.add(PROPERTY, TestJwks::uri);
    }
}
