package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.ContextLoggingExtension;
import es.crisbr.dentasoft.WithTestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An exception thrown in a filter is logged without its messages, with the request ID, and answered like any other
 * unexpected error (#126). Against the real Tomcat: MockMvc hands the exception to the test instead, so it never shows
 * what the server logs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithTestEnvironment
@Import({TestEndpoints.class, FilterExceptionLoggingTests.FailingFilterConfiguration.class})
@ExtendWith({OutputCaptureExtension.class, ContextLoggingExtension.class})
class FilterExceptionLoggingTests {

    private static final String FAILING_PATH = "/api/v1/test/filter-failure";

    private static final String REQUEST_ID = "filter-failure-0001";

    private final HttpClient client = HttpClient.newHttpClient();

    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @Test
    void anExceptionThrownInAFilterIsLoggedWithoutItsMessagesAndAnsweredAs500(CapturedOutput output) throws Exception {
        // Warm up so that lazy initialisation does not log inside the measured request.
        assertThat(get("/api/v1/test/ping").statusCode()).isEqualTo(200);
        int start = output.getOut().length();

        HttpResponse<String> response = get(FAILING_PATH);

        List<JsonNode> failures = output.getOut()
                .substring(start)
                .lines()
                .filter(line -> line.contains(FailingFilter.class.getName()))
                .map(json::readTree)
                .toList();
        assertThat(failures).singleElement().satisfies(entry -> {
            assertThat(entry.path(RequestIdFilter.MDC_KEY).asString()).isEqualTo(REQUEST_ID);
            assertThat(entry.toString())
                    .contains("java.lang.IllegalStateException")
                    .contains("java.lang.IllegalArgumentException");
        });
        assertThat(output.getAll())
                .doesNotContain(TestEndpoints.PATIENT_NAME)
                .doesNotContain(TestEndpoints.PATIENT_PHONE);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"status\":500").doesNotContain(TestEndpoints.PATIENT_NAME);
        // The filter sits inside the others: the error still carries their headers.
        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue(REQUEST_ID);
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(SecurityHeadersFilter.API_CSP);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");

        // The HTTP observation, outside the filter, still tags the request with the class of the failure.
        assertThat(serverRequests("IllegalStateException")).isNotNull();
    }

    /** The server's timer for requests failed with {@code exception}, once the observation has stopped. */
    private Timer serverRequests(String exception) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Timer timer;
        do {
            timer = meters.find("http.server.requests")
                    .tag("status", "500")
                    .tag("exception", exception)
                    .timer();
        } while (timer == null && Instant.now().isBefore(deadline) && pause());
        return timer;
    }

    private static boolean pause() throws InterruptedException {
        Thread.sleep(50);
        return true;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(RequestIdFilter.HEADER, REQUEST_ID)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Fails with invented personal data in its messages, as a filter reading a request might. */
    static final class FailingFilter implements Filter {

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain) {
            throw new IllegalStateException(
                    "Duplicate patient " + TestEndpoints.PATIENT_NAME + ", phone " + TestEndpoints.PATIENT_PHONE,
                    new IllegalArgumentException("Rejected value " + TestEndpoints.PATIENT_PHONE));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingFilterConfiguration {

        /** After every filter of the application, so that each of them has run when it fails. */
        @Bean
        FilterRegistrationBean<FailingFilter> failingFilter() {
            FilterRegistrationBean<FailingFilter> registration = new FilterRegistrationBean<>(new FailingFilter());
            registration.addUrlPatterns(FAILING_PATH);
            return registration;
        }
    }
}
