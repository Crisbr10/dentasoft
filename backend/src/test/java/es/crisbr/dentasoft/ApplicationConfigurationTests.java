package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Default configuration, with no profile active. */
@SpringBootTest
@AutoConfigureMockMvc
@WithTestEnvironment
class ApplicationConfigurationTests {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private Environment environment;

    @Test
    void healthAndKubernetesProbesAreUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    void onlyHealthAndInfoEndpointsAreExposed() {
        assertThat(mvc.get().uri("/actuator/env").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/beans").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void usesVirtualThreadsAndGracefulShutdown() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }

    @Test
    void apiDocsAreDisabledWithoutProfile() {
        for (String path : List.of("/v3/api-docs", "/swagger-ui/index.html")) {
            // Not public without springdoc: a 401 without a token, and nothing to find with one.
            assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                    .hasStatus(HttpStatus.NOT_FOUND);
        }
    }
}
