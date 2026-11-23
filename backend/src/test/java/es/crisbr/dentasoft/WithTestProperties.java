package es.crisbr.dentasoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * The properties of {@link WithTestEnvironment} without its Redis container, for tests that bring their own Redis
 * (or none). Uses the reserved {@code .test} domain so no real host ends up in the tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(
        properties = {
            "dentasoft.urls.app=" + WithTestEnvironment.APP,
            "dentasoft.urls.api=" + WithTestEnvironment.API,
            "dentasoft.urls.auth=" + WithTestEnvironment.AUTH,
            "dentasoft.rate-limit.key=" + WithTestEnvironment.RATE_LIMIT_KEY
        })
public @interface WithTestProperties {}
