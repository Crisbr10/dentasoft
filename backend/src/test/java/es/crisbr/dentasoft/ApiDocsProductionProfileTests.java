package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@WithTestEnvironment
class ApiDocsProductionProfileTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void hidesOpenApiDocumentAndSwaggerUi() {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html")) {
            // Outside the local profile springdoc is off, so its paths are not public: a 401 without a token...
            assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.UNAUTHORIZED);
            // ...and no documentation with one.
            assertThat(mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyText()
                    .doesNotContain("\"openapi\"", "<html");
        }
    }
}
