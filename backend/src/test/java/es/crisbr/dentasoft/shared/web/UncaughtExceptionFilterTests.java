package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * The branches of {@link UncaughtExceptionFilter} that {@code GlobalExceptionHandler} does not answer; the one it does
 * is in {@link FilterExceptionLoggingTests}, against the real Tomcat.
 */
@ExtendWith(OutputCaptureExtension.class)
class UncaughtExceptionFilterTests {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test/filter-failure");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final List<Exception> resolved = new ArrayList<>();

    @Test
    void anUnresolvedExceptionIsLoggedWithoutItsMessagesAndAnsweredAs500(CapturedOutput output) throws Exception {
        ServerRequestObservationContext observation = observe();
        IllegalStateException failure = failure();

        run(resolving(null), failure);

        assertThat(resolved).containsExactly(failure);
        assertThat(response.getStatus()).isEqualTo(500);
        // A tracing handler would record the messages of an error given to the observation.
        assertThat(observation.getError()).isNull();
        assertLoggedWithoutMessages(output);
    }

    @Test
    void anExceptionAnsweredWithAViewIsAnsweredAs500Instead(CapturedOutput output) throws Exception {
        ServerRequestObservationContext observation = observe();

        run(resolving(new ModelAndView("error")), failure());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(observation.getError()).isNull();
        assertLoggedWithoutMessages(output);
    }

    @Test
    void aThrowableThatIsNotAnExceptionIsAnsweredAs500WithoutTheResolver(CapturedOutput output) throws Exception {
        run(resolving(new ModelAndView()), new AssertionError("Duplicate patient " + TestEndpoints.PATIENT_NAME));

        assertThat(resolved).isEmpty();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(output.getAll())
                .contains("Unhandled exception java.lang.AssertionError")
                .doesNotContain(TestEndpoints.PATIENT_NAME);
    }

    @Test
    void aCommittedResponseIsLoggedAndRethrownWithoutMessages(CapturedOutput output) {
        response.setCommitted(true);

        assertThatThrownBy(() -> run(resolving(new ModelAndView()), failure()))
                .isNotInstanceOf(IllegalStateException.class)
                .hasToString("[redacted] java.lang.IllegalStateException")
                .hasRootCauseMessage("java.lang.IllegalArgumentException");
        assertThat(resolved).isEmpty();
        assertLoggedWithoutMessages(output);
    }

    @Test
    void aClientThatWentAwayGoesOnToTheContainerUntouched(CapturedOutput output) {
        IOException disconnected = new IOException("Broken pipe");

        assertThatThrownBy(() -> run(resolving(new ModelAndView()), disconnected))
                .isSameAs(disconnected);
        assertThat(resolved).isEmpty();
        assertThat(output.getAll()).doesNotContain("Unhandled exception");
    }

    @Test
    void anErrorOfTheVirtualMachineGoesOnToTheContainerUntouched() {
        StackOverflowError error = new StackOverflowError();

        assertThatThrownBy(() -> run(resolving(new ModelAndView()), error)).isSameAs(error);
        assertThat(resolved).isEmpty();
    }

    private void run(HandlerExceptionResolver resolver, Throwable thrown) throws Exception {
        FilterChain failing = (req, res) -> {
            if (thrown instanceof IOException io) {
                throw io;
            }
            if (thrown instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) thrown;
        };
        new UncaughtExceptionFilter(resolver).doFilter(request, response, failing);
    }

    private HandlerExceptionResolver resolving(@Nullable ModelAndView answer) {
        return (req, res, handler, exception) -> {
            resolved.add(exception);
            return answer;
        };
    }

    private ServerRequestObservationContext observe() {
        ServerRequestObservationContext observation = new ServerRequestObservationContext(request, response);
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);
        return observation;
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(
                "Duplicate patient " + TestEndpoints.PATIENT_NAME,
                new IllegalArgumentException("Rejected value " + TestEndpoints.PATIENT_PHONE));
    }

    private static void assertLoggedWithoutMessages(CapturedOutput output) {
        assertThat(output.getAll())
                .contains("Unhandled exception java.lang.IllegalStateException")
                .contains("java.lang.IllegalArgumentException")
                .doesNotContain(TestEndpoints.PATIENT_NAME)
                .doesNotContain(TestEndpoints.PATIENT_PHONE);
    }
}
