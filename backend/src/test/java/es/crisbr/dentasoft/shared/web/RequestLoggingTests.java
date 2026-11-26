package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.ContextLoggingExtension;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.WithTestEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Logs are JSON, carry the request ID on every line of a request and never the personal data of an error. */
@SpringBootTest
@AutoConfigureMockMvc
@WithTestEnvironment
@Import(TestEndpoints.class)
@ExtendWith({OutputCaptureExtension.class, ContextLoggingExtension.class})
class RequestLoggingTests {

    private static final String REQUEST_ID = "log-test-0001";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    @Test
    void everyLineOfARequestIsJsonWithItsIdAndNoPersonalData(CapturedOutput output) throws InterruptedException {
        // Warm up so that lazy initialisation (Redis connection, MVC) does not log inside the measured request.
        assertThat(mvc.get().uri("/api/v1/test/ping").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatusOk();
        int start = output.getOut().length();
        logFromAnotherThread();

        assertThat(mvc.get()
                        .uri("/api/v1/test/failure")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser())
                        .header("X-Request-Id", REQUEST_ID))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        // Every line is JSON, but only those of the request thread belong to the request: MockMvc runs it here.
        List<JsonNode> entries = output.getOut()
                .substring(start)
                .lines()
                .filter(line -> !line.isBlank())
                .map(json::readTree)
                .toList();
        String requestThread = Thread.currentThread().getName();
        List<JsonNode> request = entries.stream()
                .filter(entry -> requestThread.equals(
                        entry.path("process").path("thread").path("name").asString()))
                .toList();
        assertThat(request).hasSizeGreaterThanOrEqualTo(2);
        assertThat(request)
                .allSatisfy(
                        entry -> assertThat(entry.path(RequestIdFilter.MDC_KEY).asString())
                                .isEqualTo(REQUEST_ID));
        assertThat(request)
                .map(JsonNode::toString)
                .anySatisfy(line -> assertThat(line).contains("Loading patient 0190a6e8-0000-7000-8000-000000000001"))
                .anySatisfy(line -> assertThat(line)
                        .contains("java.lang.IllegalStateException")
                        .contains("java.lang.IllegalArgumentException")
                        .contains("TestEndpoints.failure"));
        assertThat(output.getAll())
                .doesNotContain(TestEndpoints.PATIENT_NAME)
                .doesNotContain(TestEndpoints.PATIENT_PHONE);
    }

    /**
     * Output capture sees every thread. A client of another test context (Lettuce, after its event loop shut down)
     * may log at any time without a request ID, as in issue #101: reproduce it inside the measured window.
     */
    private static void logFromAnotherThread() throws InterruptedException {
        Thread other = new Thread(
                () -> LoggerFactory.getLogger("io.netty.util.concurrent.DefaultPromise.rejectedExecution")
                        .warn("Failed to submit a listener notification task. Event loop shut down?"),
                "lettuce-eventExecutorLoop-test");
        other.start();
        other.join();
    }
}
