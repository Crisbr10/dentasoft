package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestUrls;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

class CorsPropertiesTests {

    @Nested
    @SpringBootTest
    @WithTestUrls
    class ByDefault {

        @Autowired
        private CorsProperties cors;

        @Test
        void allowsOnlyTheFrontendUrl() {
            assertThat(cors.allowedOrigins()).containsExactly(WithTestUrls.APP);
        }
    }

    @Nested
    @SpringBootTest
    @WithTestUrls
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
