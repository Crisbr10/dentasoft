package es.crisbr.dentasoft.shared.web;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Bucket4j buckets in Redis, through the Lettuce client that Spring Boot configures ({@code spring.data.redis.*}).
 *
 * <p>The connection is opened on first use, so the application starts even if Redis is down. While one request
 * is connecting, the others fail fast instead of queueing behind it, and {@link RateLimiter} sends them to its
 * fallback.
 */
class RedisBuckets implements RateLimiter.DistributedBuckets, DisposableBean {

    /** Keys live until the bucket would be full again, plus this margin. */
    private static final Duration KEY_EXPIRATION_MARGIN = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    private final LettuceConnectionFactory connectionFactory;
    private final ReentrantLock connecting = new ReentrantLock();
    private volatile @Nullable LettuceBasedProxyManager<String> proxyManager;
    private @Nullable StatefulRedisConnection<String, byte[]> connection;

    RedisBuckets(LettuceConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ConsumptionProbe tryConsume(String key, BucketConfiguration configuration) {
        return proxyManager().getProxy(key, () -> configuration).tryConsumeAndReturnRemaining(1);
    }

    private LettuceBasedProxyManager<String> proxyManager() {
        LettuceBasedProxyManager<String> current = proxyManager;
        if (current != null) {
            return current;
        }
        if (!connecting.tryLock()) {
            throw new IllegalStateException("Connection to Redis in progress");
        }
        try {
            if (proxyManager == null) {
                AbstractRedisClient client = connectionFactory.getRequiredNativeClient();
                if (!(client instanceof RedisClient standalone)) {
                    throw new IllegalStateException("Rate limiting supports standalone Redis only");
                }
                connection = standalone.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                        .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                KEY_EXPIRATION_MARGIN))
                        .requestTimeout(REQUEST_TIMEOUT)
                        .build();
            }
            return proxyManager;
        } finally {
            connecting.unlock();
        }
    }

    @Override
    public void destroy() {
        StatefulRedisConnection<String, byte[]> current = connection;
        if (current != null) {
            current.close();
        }
    }
}
