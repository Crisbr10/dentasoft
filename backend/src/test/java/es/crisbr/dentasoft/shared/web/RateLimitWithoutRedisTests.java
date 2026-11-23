package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** With Redis unreachable the application keeps serving requests, limited per instance, and health stays UP. */
@SpringBootTest(properties = "dentasoft.rate-limit.anonymous-per-minute=2")
@AutoConfigureMockMvc
@WithTestProperties
@Import(TestEndpoints.class)
class RateLimitWithoutRedisTests {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private HealthEndpoint health;

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry properties) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> closedPort);
    }

    @Test
    void requestsPassUnderThePerInstanceLimit() {
        assertThat(mvc.get().uri("/api/v1/test/ping")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/test/ping")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/test/ping")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(registry.get(RateLimiter.REDIS_AVAILABLE_METRIC).gauge().value()).isZero();
        assertThat(registry.get(RateLimiter.FALLBACK_METRIC).counter().count()).isEqualTo(3);
    }

    @Test
    void healthStaysUpBecauseRedisIsNotEssential() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
