package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DentasoftUrlsTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SharedConfiguration.class);

    @Test
    void bindsTheThreeUrls() {
        runner.withPropertyValues(
                        "dentasoft.urls.app=https://app.dentasoft.test",
                        "dentasoft.urls.api=https://api.dentasoft.test",
                        "dentasoft.urls.auth=https://auth.dentasoft.test")
                .run(context -> assertThat(context.getBean(DentasoftUrls.class))
                        .isEqualTo(new DentasoftUrls(
                                "https://app.dentasoft.test",
                                "https://api.dentasoft.test",
                                "https://auth.dentasoft.test")));
    }

    @Test
    void failsToStartWithoutUrlsAndNamesEveryMissingVariable() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("DENTASOFT_URLS_APP")
                    .hasMessageContaining("DENTASOFT_URLS_API")
                    .hasMessageContaining("DENTASOFT_URLS_AUTH");
        });
    }

    @Test
    void failureNamesOnlyTheMissingVariable() {
        runner.withPropertyValues(
                        "dentasoft.urls.app=https://app.dentasoft.test",
                        "dentasoft.urls.auth=https://auth.dentasoft.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("DENTASOFT_URLS_API")
                            .message()
                            .doesNotContain("DENTASOFT_URLS_APP")
                            .doesNotContain("DENTASOFT_URLS_AUTH");
                });
    }

    @Test
    void rejectsAnInvalidUrl() {
        runner.withPropertyValues(
                        "dentasoft.urls.app=not a url",
                        "dentasoft.urls.api=https://api.dentasoft.test",
                        "dentasoft.urls.auth=https://auth.dentasoft.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("dentasoft.urls");
                });
    }
}
