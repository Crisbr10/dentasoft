package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.shared.DentasoftUrls;
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
    void serverUrlComesFromDentasoftUrlsApi() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.servers[0].url")
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void localProfileProvidesLocalhostUrls(@Autowired DentasoftUrls urls) {
        assertThat(urls)
                .isEqualTo(new DentasoftUrls(
                        "http://localhost:4200", "http://localhost:8080", "http://localhost:8180"));
    }

    @Test
    void exposesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}
