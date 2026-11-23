package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitFilterTests {

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsAtLeastOne() {
        assertThat(RateLimitFilter.retryAfterSeconds(0)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1_000_000_000L)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1_000_000_001L)).isEqualTo(2);
        assertThat(RateLimitFilter.retryAfterSeconds(59_500_000_000L)).isEqualTo(60);
    }
}
