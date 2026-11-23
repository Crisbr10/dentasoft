package es.crisbr.dentasoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Supplies the public URLs that every non-local profile must receive from the environment.
 * Uses the reserved {@code .test} domain so no real host ends up in the tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(
        properties = {
            "dentasoft.urls.app=" + WithTestUrls.APP,
            "dentasoft.urls.api=" + WithTestUrls.API,
            "dentasoft.urls.auth=" + WithTestUrls.AUTH
        })
public @interface WithTestUrls {

    String APP = "https://app.dentasoft.test";
    String API = "https://api.dentasoft.test";
    String AUTH = "https://auth.dentasoft.test";
}
