package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RateLimitPropertiesTests {

    private static final String VALID_KEY = "dentasoft.rate-limit.key=unit-test-key-0123456789-0123456789";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsTheDefaults() {
        runner.withPropertyValues(VALID_KEY).run(context -> {
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.anonymousPerMinute()).isEqualTo(120);
            assertThat(properties.userPerMinute()).isEqualTo(600);
        });
    }

    @Test
    void failsToStartWithoutTheKeyAndNamesTheVariable() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("DENTASOFT_RATE_LIMIT_KEY");
        });
    }

    @Test
    void doesNotNeedTheKeyWhenDisabled() {
        runner.withPropertyValues("dentasoft.rate-limit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void rejectsAShortKey() {
        runner.withPropertyValues("dentasoft.rate-limit.key=too-short").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("at least 32 characters");
        });
    }

    @Test
    void rejectsANonPositiveLimit() {
        runner.withPropertyValues(VALID_KEY, "dentasoft.rate-limit.anonymous-per-minute=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("anonymousPerMinute");
                });
    }

    @Test
    void neverPrintsTheKey() {
        runner.withPropertyValues(VALID_KEY)
                .run(context -> assertThat(
                                context.getBean(RateLimitProperties.class).toString())
                        .doesNotContain("unit-test-key"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertiesConfiguration {}
}
