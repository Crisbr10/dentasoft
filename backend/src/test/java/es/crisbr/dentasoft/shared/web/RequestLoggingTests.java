package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestEnvironment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Logs are JSON, carry the request ID on every line of a request and never the personal data of an error. */
@SpringBootTest
@AutoConfigureMockMvc
@WithTestEnvironment
@Import(TestEndpoints.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTests {

    private static final String REQUEST_ID = "log-test-0001";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    /**
     * Logback is initialised once per JVM, by the first test context, which may use another profile (local logs
     * plain text). Re-initialise it with this context's configuration so the production format is what is tested.
     */
    @BeforeEach
    void useTheLoggingConfigurationOfThisContext(@Autowired ConfigurableEnvironment environment) {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        logging.cleanUp();
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(environment), null, null);
    }

    @Test
    void everyLineOfARequestIsJsonWithItsIdAndNoPersonalData(CapturedOutput output) {
        // Warm up so that lazy initialisation (Redis connection, MVC) does not log inside the measured request.
        assertThat(mvc.get().uri("/api/v1/test/ping")).hasStatusOk();
        int start = output.getOut().length();

        assertThat(mvc.get().uri("/api/v1/test/failure").header("X-Request-Id", REQUEST_ID))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        List<String> lines = output.getOut().substring(start).lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines).allSatisfy(line -> {
            JsonNode entry = json.readTree(line);
            assertThat(entry.path(RequestIdFilter.MDC_KEY).asString()).isEqualTo(REQUEST_ID);
        });
        assertThat(lines)
                .anySatisfy(line -> assertThat(line).contains("Loading patient 0190a6e8-0000-7000-8000-000000000001"))
                .anySatisfy(line -> assertThat(line)
                        .contains("java.lang.IllegalStateException")
                        .contains("java.lang.IllegalArgumentException")
                        .contains("TestEndpoints.failure"));
        assertThat(output.getAll())
                .doesNotContain(TestEndpoints.PATIENT_NAME)
                .doesNotContain(TestEndpoints.PATIENT_PHONE);
    }
}
