package es.crisbr.dentasoft.shared.web;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets shared by every instance through Redis, with a per-instance fallback.
 *
 * <p>If Redis fails, requests are not blocked: they are counted against an in-memory bucket of this instance
 * until Redis answers again, which is retried every {@link #REDIS_RETRY_INTERVAL}. Meanwhile a warning is logged at
 * most once per {@link #WARNING_INTERVAL} and the {@value #REDIS_AVAILABLE_METRIC} gauge drops to 0.
 */
class RateLimiter {

    enum Scope {
        ANONYMOUS,
        USER
    }

    /** The shared buckets; any exception means they cannot be used right now. */
    interface DistributedBuckets {
        ConsumptionProbe tryConsume(String key, BucketConfiguration configuration);
    }

    static final String REDIS_AVAILABLE_METRIC = "dentasoft.rate.limit.redis.available";
    static final String FALLBACK_METRIC = "dentasoft.rate.limit.fallback";
    static final String REJECTED_METRIC = "dentasoft.rate.limit.rejected";
    static final Duration REDIS_RETRY_INTERVAL = Duration.ofSeconds(5);
    static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_LOCAL_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final DistributedBuckets redis;
    private final InstantSource clock;
    private final Map<Scope, BucketConfiguration> configurations = new EnumMap<>(Scope.class);
    private final LocalBuckets localBuckets = new LocalBuckets();
    private final AtomicReference<Instant> redisRetryAt = new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.MIN);
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    private final AtomicLong fallbacksSinceWarning = new AtomicLong();
    private final Counter fallbacks;
    private final Map<Scope, Counter> rejections = new EnumMap<>(Scope.class);

    RateLimiter(RateLimitProperties properties, DistributedBuckets redis, MeterRegistry registry, InstantSource clock) {
        this.redis = redis;
        this.clock = clock;
        configurations.put(Scope.ANONYMOUS, perMinute(properties.anonymousPerMinute()));
        configurations.put(Scope.USER, perMinute(properties.userPerMinute()));
        Gauge.builder(REDIS_AVAILABLE_METRIC, redisAvailable, available -> available.get() ? 1 : 0)
                .description("1 while Redis holds the rate limits, 0 while the per-instance fallback is in use")
                .register(registry);
        fallbacks = Counter.builder(FALLBACK_METRIC)
                .description("Requests checked against the per-instance fallback because Redis was unavailable")
                .register(registry);
        for (Scope scope : Scope.values()) {
            rejections.put(
                    scope,
                    Counter.builder(REJECTED_METRIC)
                            .description("Requests rejected with 429")
                            .tag("scope", scope.name().toLowerCase())
                            .register(registry));
        }
    }

    ConsumptionProbe tryConsume(String key, Scope scope) {
        BucketConfiguration configuration = configurations.get(scope);
        ConsumptionProbe probe = distributedOrLocal(key, configuration);
        if (!probe.isConsumed()) {
            rejections.get(scope).increment();
        }
        return probe;
    }

    private ConsumptionProbe distributedOrLocal(String key, BucketConfiguration configuration) {
        Instant now = clock.instant();
        if (!now.isBefore(redisRetryAt.get())) {
            try {
                ConsumptionProbe probe = redis.tryConsume(key, configuration);
                if (redisAvailable.compareAndSet(false, true)) {
                    log.info("Redis is available again for rate limiting");
                }
                return probe;
            } catch (RuntimeException ex) {
                redisRetryAt.set(now.plus(REDIS_RETRY_INTERVAL));
                redisAvailable.set(false);
                warnAtMostOncePerInterval(now, ex);
            }
        }
        fallbacks.increment();
        fallbacksSinceWarning.incrementAndGet();
        return localBuckets.get(key, configuration).tryConsumeAndReturnRemaining(1);
    }

    private void warnAtMostOncePerInterval(Instant now, RuntimeException ex) {
        Instant previous = lastWarning.get();
        if (now.isBefore(previous.plus(WARNING_INTERVAL)) || !lastWarning.compareAndSet(previous, now)) {
            return;
        }
        // Only the exception class: messages from the Redis client may carry connection details.
        log.warn(
                "Redis is unavailable for rate limiting ({}); using the per-instance limit. "
                        + "Requests checked locally since the last warning: {}",
                ex.getClass().getName(),
                fallbacksSinceWarning.getAndSet(0));
    }

    private static BucketConfiguration perMinute(int requests) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(requests).refillGreedy(requests, Duration.ofMinutes(1)))
                .build();
    }

    /** In-memory buckets, bounded so a flood of distinct clients cannot exhaust the heap. */
    private static final class LocalBuckets {

        private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_LOCAL_BUCKETS;
            }
        };

        synchronized Bucket get(String key, BucketConfiguration configuration) {
            return buckets.computeIfAbsent(key, ignored -> Bucket.builder()
                    .addLimit(configuration.getBandwidths()[0])
                    .build());
        }
    }
}
