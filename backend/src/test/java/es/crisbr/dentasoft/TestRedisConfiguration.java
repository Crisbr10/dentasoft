package es.crisbr.dentasoft;

import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis for integration tests, with the same image as {@code infra/} ({@code REDIS_IMAGE}).
 *
 * <p>As with {@link TestPostgresConfiguration}, one container is shared by every Spring context of the test run and
 * it is stopped when the JVM exits: a container per context kept one Redis running for every cached context. Each new
 * context empties it first, so it starts with no rate-limit buckets, as it did with a container of its own.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRedisConfiguration {

    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.8.4-alpine");

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> container;

    @Bean
    DynamicPropertyRegistrar redisProperties() {
        GenericContainer<?> redis = container();
        flush(redis);
        String host = redis.getHost();
        int port = redis.getMappedPort(REDIS_PORT);
        return registry -> {
            registry.add("spring.data.redis.host", () -> host);
            registry.add("spring.data.redis.port", () -> port);
        };
    }

    private static synchronized GenericContainer<?> container() {
        if (container == null) {
            GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);
            redis.start();
            // Stops it at JVM exit even where the Testcontainers reaper (Ryuk) is disabled.
            Runtime.getRuntime().addShutdownHook(new Thread(redis::stop, "redis-test-container-stop"));
            container = redis;
        }
        return container;
    }

    private static void flush(GenericContainer<?> redis) {
        try {
            ExecResult result = redis.execInContainer("redis-cli", "FLUSHALL");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("redis-cli FLUSHALL exited with " + result.getExitCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not empty the test Redis", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while emptying the test Redis", e);
        }
    }
}
