package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestEnvironment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.validation.FieldError;

class CorsPropertiesTests {

    /** Loads {@code application.yml}, so the variable goes through the same placeholder as in production. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CorsOnly.class);

    @Test
    void failsToStartWithAnInvalidOriginInTheEnvironmentVariable() {
        runner.withPropertyValues("DENTASOFT_CORS_ALLOWED_ORIGINS=https://one.dentasoft.test,not an origin")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOfSatisfying(
                                    BindValidationException.class,
                                    ex -> assertThat(ex.getValidationErrors().getAllErrors())
                                            .singleElement()
                                            .isInstanceOfSatisfying(FieldError.class, error -> {
                                                assertThat(error.getObjectName())
                                                        .isEqualTo("dentasoft.cors");
                                                assertThat(error.getField()).isEqualTo("allowedOrigins[1]");
                                                assertThat(error.getCode()).isEqualTo("URL");
                                            }));
                });
    }

    @Test
    void startsWithValidOriginsInTheEnvironmentVariable() {
        runner.withPropertyValues("DENTASOFT_CORS_ALLOWED_ORIGINS=https://one.dentasoft.test")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("https://one.dentasoft.test"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class CorsOnly {}

    @Nested
    @SpringBootTest
    @WithTestEnvironment
    class ByDefault {

        @Autowired
        private CorsProperties cors;

        @Test
        void allowsOnlyTheFrontendUrl() {
            assertThat(cors.allowedOrigins()).containsExactly(WithTestEnvironment.APP);
        }
    }

    @Nested
    @SpringBootTest
    @WithTestEnvironment
    @TestPropertySource(
            properties = "DENTASOFT_CORS_ALLOWED_ORIGINS=https://one.dentasoft.test,https://two.dentasoft.test")
    class FromEnvironmentVariable {

        @Autowired
        private CorsProperties cors;

        @Test
        void readsACommaSeparatedList() {
            assertThat(cors.allowedOrigins())
                    .containsExactly("https://one.dentasoft.test", "https://two.dentasoft.test");
        }
    }
}
