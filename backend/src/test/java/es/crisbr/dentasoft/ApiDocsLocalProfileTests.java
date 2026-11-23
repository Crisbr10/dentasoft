package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ApiDocsLocalProfileTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void exposesOpenApiDocument() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.openapi")
                .asString()
                .startsWith("3.");
    }

    @Test
    void exposesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}
