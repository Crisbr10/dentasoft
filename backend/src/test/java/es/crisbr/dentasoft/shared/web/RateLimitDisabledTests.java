package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.TestPostgresConfiguration;
import es.crisbr.dentasoft.WithTestEnvironment;
import es.crisbr.dentasoft.shared.Quotas;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code DENTASOFT_RATE_LIMIT_ENABLED=false} starts without the key and without the rate-limit beans, and with the
 * quotas off too, since they share the limits' buckets.
 */
@SpringBootTest
@Import(TestPostgresConfiguration.class)
@TestPropertySource(
        properties = {
            "dentasoft.urls.app=" + WithTestEnvironment.APP,
            "dentasoft.urls.api=" + WithTestEnvironment.API,
            "dentasoft.urls.auth=" + WithTestEnvironment.AUTH,
            "dentasoft.rate-limit.enabled=false",
            "dentasoft.rate-limit.key=",
            "dentasoft.keycloak-admin.client-secret=" + FakeKeycloak.CLIENT_SECRET
        })
class RateLimitDisabledTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutTheKeyAndWithoutTheLimiter() {
        assertThat(context.getBeanNamesForType(RateLimiter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RedisBuckets.class)).isEmpty();
    }

    @Test
    void quotasAllowEverything() {
        Quotas quotas = context.getBean(Quotas.class);

        for (int i = 0; i < 3; i++) {
            assertThat(quotas.tryAcquire("invitation", "clinic", 1, Duration.ofDays(1))
                            .allowed())
                    .isTrue();
        }
    }
}
