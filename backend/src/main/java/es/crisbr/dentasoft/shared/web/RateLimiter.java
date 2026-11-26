package es.crisbr.dentasoft.shared.web;

import io.github.bucket4j.Bandwidth;
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
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets shared by every instance through Redis, with a per-instance fallback.
 *
 * <p>If Redis fails, requests are not blocked: they are counted against an in-memory bucket of this instance
 * until Redis answers again. Once a call has failed, a single request probes Redis every
 * {@link #REDIS_RETRY_INTERVAL} and the others keep using the local bucket without waiting. Each Redis call times out
 * after 1 second ({@link RedisBuckets}), so only the requests in flight when Redis starts to hang, and then one probe
 * per interval, wait for it. Meanwhile a warning is
 * logged at most once per {@link #WARNING_INTERVAL} and the {@value #REDIS_AVAILABLE_METRIC} gauge drops to 0.
 */
class RateLimiter {

    enum Scope {
        ANONYMOUS,
        USER
    }

    /** The shared buckets; any exception means they cannot be used right now. */
    interface DistributedBuckets {
        ConsumptionProbe tryConsume(String key, BucketConfiguration configuration);

        /** Gives back one request counted by {@link #tryConsume}, never above the capacity. */
        void refund(String key, BucketConfiguration configuration);
    }

    static final String REDIS_AVAILABLE_METRIC = "dentasoft.rate.limit.redis.available";
    static final String FALLBACK_METRIC = "dentasoft.rate.limit.fallback";
    static final String REJECTED_METRIC = "dentasoft.rate.limit.rejected";
    static final String EVICTED_METRIC = "dentasoft.rate.limit.fallback.evicted";
    static final Duration REDIS_RETRY_INTERVAL = Duration.ofSeconds(5);
    static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_LOCAL_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final DistributedBuckets redis;
    private final InstantSource clock;
    private final Map<Scope, BucketConfiguration> configurations = new EnumMap<>(Scope.class);
    private final LocalBuckets localBuckets;
    private final AtomicReference<Instant> redisRetryAt = new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.MIN);
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    private final AtomicLong fallbacksSinceWarning = new AtomicLong();
    private final Counter fallbacks;
    private final Map<Scope, Counter> rejections = new EnumMap<>(Scope.class);

    RateLimiter(RateLimitProperties properties, DistributedBuckets redis, MeterRegistry registry, InstantSource clock) {
        this.redis = redis;
        this.clock = clock;
        localBuckets = new LocalBuckets(Counter.builder(EVICTED_METRIC)
                .description("Per-instance buckets dropped because the fallback was full")
                .register(registry));
        configurations.put(Scope.ANONYMOUS, perMinute(properties.anonymousPerMinute()));
        configurations.put(Scope.USER, perMinute(properties.userPerMinute()));
        Gauge.builder(REDIS_AVAILABLE_METRIC, redisAvailable, available -> available.get() ? 1 : 0)
                .description("1 while Redis holds the rate limits, 0 while the per-instance fallback is in use")
                .register(registry);
        fallbacks = Counter.builder(FALLBACK_METRIC)
                .description("Checks and counts done against the per-instance fallback because Redis was unavailable")
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

    /**
     * Gives back one request of {@code key} counted by {@link #tryConsume}: for limits that count every request up
     * front, so concurrent requests cannot slip through, but only keep some once they are done (the client IP limit
     * keeps the requests that end without a user).
     */
    void refund(String key, Scope scope) {
        BucketConfiguration configuration = configurations.get(scope);
        distributedOrLocal(
                buckets -> {
                    buckets.refund(key, configuration);
                    return null;
                },
                local -> {
                    local.get(key, configuration).addTokens(1);
                    return null;
                });
    }

    /**
     * Counts one use of {@code key} against its own {@code configuration}, with the same Redis fallback as the request
     * limits but without their metrics: for {@link RedisQuotas}.
     */
    ConsumptionProbe tryConsume(String key, BucketConfiguration configuration) {
        return distributedOrLocal(key, configuration);
    }

    private ConsumptionProbe distributedOrLocal(String key, BucketConfiguration configuration) {
        return distributedOrLocal(
                buckets -> buckets.tryConsume(key, configuration),
                local -> local.get(key, configuration).tryConsumeAndReturnRemaining(1));
    }

    private <T> T distributedOrLocal(Function<DistributedBuckets, T> distributed, Function<LocalBuckets, T> local) {
        Instant now = clock.instant();
        if (mayUseRedis(now)) {
            try {
                T probe = distributed.apply(redis);
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
        return local.apply(localBuckets);
    }

    /**
     * While Redis is up every request uses it. Once it has failed, only the request that wins the compare-and-set
     * after {@link #REDIS_RETRY_INTERVAL} probes it; it moves the retry time forward first.
     */
    private boolean mayUseRedis(Instant now) {
        if (redisAvailable.get()) {
            return true;
        }
        Instant retryAt = redisRetryAt.get();
        return !now.isBefore(retryAt) && redisRetryAt.compareAndSet(retryAt, now.plus(REDIS_RETRY_INTERVAL));
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

    /**
     * In-memory buckets, bounded so a flood of distinct clients cannot exhaust the heap. A dropped bucket starts
     * again full, so evictions weaken the limit; the {@value #EVICTED_METRIC} counter shows that pressure.
     */
    private static final class LocalBuckets {

        private final Counter evictions;
        private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                boolean full = size() > MAX_LOCAL_BUCKETS;
                if (full) {
                    evictions.increment();
                }
                return full;
            }
        };

        LocalBuckets(Counter evictions) {
            this.evictions = evictions;
        }

        synchronized Bucket get(String key, BucketConfiguration configuration) {
            return buckets.computeIfAbsent(key, ignored -> {
                var builder = Bucket.builder();
                for (Bandwidth bandwidth : configuration.getBandwidths()) {
                    builder.addLimit(bandwidth);
                }
                return builder.build();
            });
        }
    }
}
