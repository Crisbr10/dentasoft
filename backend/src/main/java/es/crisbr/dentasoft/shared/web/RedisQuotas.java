package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.Quotas;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * {@link Quotas} on the buckets of the {@link RateLimiter}: shared through Redis, per instance while Redis fails. Each
 * rejection is counted in {@value #REJECTED_METRIC}, tagged with the quota's name.
 */
class RedisQuotas implements Quotas {

    static final String KEY_PREFIX = "dentasoft:quota:";
    static final String REJECTED_METRIC = "dentasoft.quota.rejected";

    /** Names and keys go into Redis keys and metric tags: fixed names, and ids or codes as keys. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final RateLimiter limiter;
    private final MeterRegistry registry;

    RedisQuotas(RateLimiter limiter, MeterRegistry registry) {
        this.limiter = limiter;
        this.registry = registry;
    }

    @Override
    public Decision tryAcquire(String name, String key, int limit, Duration period) {
        if (!NAME.matcher(name).matches() || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Quota names are fixed lowercase words and keys are ids");
        }
        if (limit < 1 || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("A quota allows at least one use per positive period");
        }
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit).refillGreedy(limit, period))
                .build();
        // The limit and period are part of the key: changing them starts a new bucket instead of reusing one built
        // with another configuration.
        String bucket = KEY_PREFIX + name + ":" + limit + "-" + period.toSeconds() + ":" + key;
        ConsumptionProbe probe = limiter.tryConsume(bucket, configuration);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        Counter.builder(REJECTED_METRIC)
                .description("Uses rejected because their quota was exhausted")
                .tag("quota", name)
                .register(registry)
                .increment();
        return new Decision(
                false, Duration.ofSeconds(RateLimitFilter.retryAfterSeconds(probe.getNanosToWaitForRefill())));
    }
}
