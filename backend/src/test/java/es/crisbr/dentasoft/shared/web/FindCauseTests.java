package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FindCauseTests {

    @Test
    void findsACauseDeepInTheChain() {
        RequestBodyTooLargeException tooLarge = new RequestBodyTooLargeException();
        IllegalStateException wrapper = new IllegalStateException("x", new IllegalArgumentException("y", tooLarge));

        assertThat(GlobalExceptionHandler.findCause(wrapper, RequestBodyTooLargeException.class))
                .isSameAs(tooLarge);
    }

    @Test
    void stopsAtACyclicCauseChain() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);

        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThat(GlobalExceptionHandler.findCause(first, RequestBodyTooLargeException.class))
                        .isNull());
    }
}
