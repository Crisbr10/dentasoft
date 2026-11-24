package es.crisbr.dentasoft.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The platform credentials are required: the application does not start without them (as with dentasoft.urls). */
class PlatformDataSourcePropertiesTests {

    private static final String URL = "jdbc:postgresql://db.dentasoft.test:5432/dentasoft";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

    @Test
    void bindsTheConnection() {
        runner.withPropertyValues(
                        "dentasoft.platform.datasource.url=" + URL,
                        "dentasoft.platform.datasource.username=dentasoft_platform",
                        "dentasoft.platform.datasource.password=test-only-password")
                .run(context -> assertThat(context.getBean(PlatformDataSourceProperties.class))
                        .isEqualTo(new PlatformDataSourceProperties(URL, "dentasoft_platform", "test-only-password")));
    }

    @Test
    void failsToStartWithoutCredentialsAndNamesEveryMissingVariable() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("DENTASOFT_PLATFORM_DATASOURCE_URL")
                    .hasMessageContaining("DENTASOFT_PLATFORM_DATASOURCE_USERNAME")
                    .hasMessageContaining("DENTASOFT_PLATFORM_DATASOURCE_PASSWORD");
        });
    }

    @Test
    void failsToStartWithABlankPassword() {
        runner.withPropertyValues(
                        "dentasoft.platform.datasource.url=" + URL,
                        "dentasoft.platform.datasource.username=dentasoft_platform",
                        "dentasoft.platform.datasource.password= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("DENTASOFT_PLATFORM_DATASOURCE_PASSWORD")
                            .message()
                            .doesNotContain("DENTASOFT_PLATFORM_DATASOURCE_USERNAME");
                });
    }

    @Test
    void neverPrintsThePassword() {
        assertThat(new PlatformDataSourceProperties(URL, "dentasoft_platform", "test-only-password").toString())
                .doesNotContain("test-only-password");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PlatformDataSourceProperties.class)
    static class PropertiesOnly {}
}
