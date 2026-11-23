package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The per-instance fallback while Redis is unavailable. */
@ExtendWith(OutputCaptureExtension.class)
class RateLimiterTests {

    private static final String WARNING = "Redis is unavailable for rate limiting";

    private final MutableClock clock = new MutableClock();
    private final FlakyRedis redis = new FlakyRedis();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimiter limiter = new RateLimiter(
            new RateLimitProperties(true, 3, 5, "unit-test-key-0123456789-0123456789"), redis, registry, clock);

    @Test
    void usesRedisWhileItAnswers() {
        assertThat(limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS).isConsumed()).isTrue();

        assertThat(redis.calls).isEqualTo(1);
        assertThat(gauge()).isEqualTo(1.0);
        assertThat(registry.counter(RateLimiter.FALLBACK_METRIC).count()).isZero();
    }

    @Test
    void whenRedisFailsRequestsPassButTheLocalLimitStillApplies() {
        redis.down = true;

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS).isConsumed()).isTrue();
        }
        ConsumptionProbe fourth = limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS);

        assertThat(fourth.isConsumed()).isFalse();
        assertThat(fourth.getNanosToWaitForRefill()).isPositive();
        assertThat(limiter.tryConsume("b", RateLimiter.Scope.ANONYMOUS).isConsumed()).isTrue();
        assertThat(registry.counter(RateLimiter.FALLBACK_METRIC).count()).isEqualTo(5);
        assertThat(registry.counter(RateLimiter.REJECTED_METRIC, "scope", "anonymous").count()).isEqualTo(1);
        assertThat(gauge()).isZero();
    }

    @Test
    void usersKeepTheirOwnLimitInTheFallback() {
        redis.down = true;

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("u", RateLimiter.Scope.USER).isConsumed()).isTrue();
        }

        assertThat(limiter.tryConsume("u", RateLimiter.Scope.USER).isConsumed()).isFalse();
    }

    @Test
    void doesNotRetryRedisOnEveryRequestWhileItIsDown() {
        redis.down = true;

        for (int i = 0; i < 10; i++) {
            limiter.tryConsume("key-" + i, RateLimiter.Scope.ANONYMOUS);
        }

        assertThat(redis.calls).isEqualTo(1);

        clock.advance(RateLimiter.REDIS_RETRY_INTERVAL);
        limiter.tryConsume("again", RateLimiter.Scope.ANONYMOUS);

        assertThat(redis.calls).isEqualTo(2);
    }

    @Test
    void warnsAtMostOncePerMinute(CapturedOutput output) {
        redis.down = true;

        for (int second = 0; second < 60; second += 5) {
            limiter.tryConsume("a" + second, RateLimiter.Scope.ANONYMOUS);
            clock.advance(RateLimiter.REDIS_RETRY_INTERVAL);
        }
        assertThat(occurrences(output.getAll(), WARNING)).isEqualTo(1);

        limiter.tryConsume("later", RateLimiter.Scope.ANONYMOUS);
        assertThat(occurrences(output.getAll(), WARNING)).isEqualTo(2);
    }

    @Test
    void theWarningDoesNotCarryTheRedisErrorMessage(CapturedOutput output) {
        redis.down = true;

        limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS);

        assertThat(output.getAll()).contains(WARNING).contains(IllegalStateException.class.getName());
        assertThat(output.getAll()).doesNotContain(FlakyRedis.ERROR_MESSAGE);
    }

    @Test
    void goesBackToRedisWhenItRecovers(CapturedOutput output) {
        redis.down = true;
        limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS);
        assertThat(gauge()).isZero();

        redis.down = false;
        clock.advance(RateLimiter.REDIS_RETRY_INTERVAL);
        limiter.tryConsume("a", RateLimiter.Scope.ANONYMOUS);

        assertThat(gauge()).isEqualTo(1.0);
        assertThat(output.getAll()).contains("Redis is available again for rate limiting");
    }

    private double gauge() {
        return registry.get(RateLimiter.REDIS_AVAILABLE_METRIC).gauge().value();
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int index = text.indexOf(fragment); index >= 0; index = text.indexOf(fragment, index + 1)) {
            count++;
        }
        return count;
    }

    private static final class FlakyRedis implements RateLimiter.DistributedBuckets {

        static final String ERROR_MESSAGE = "connection refused to redis.internal.test:6379";

        boolean down;
        int calls;

        @Override
        public ConsumptionProbe tryConsume(String key, BucketConfiguration configuration) {
            calls++;
            if (down) {
                throw new IllegalStateException(ERROR_MESSAGE);
            }
            return ConsumptionProbe.consumed(1, 0);
        }
    }

    private static final class MutableClock implements InstantSource {

        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
