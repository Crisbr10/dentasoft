package es.crisbr.dentasoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * What every non-local profile must receive from the environment: the public URLs and the rate-limit key, plus a
 * Redis container. Uses the reserved {@code .test} domain so no real host ends up in the tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Import(TestRedisConfiguration.class)
@WithTestProperties
public @interface WithTestEnvironment {

    String APP = "https://app.dentasoft.test";
    String API = "https://api.dentasoft.test";
    String AUTH = "https://auth.dentasoft.test";
    String RATE_LIMIT_KEY = "test-only-rate-limit-key-0123456789";
}
