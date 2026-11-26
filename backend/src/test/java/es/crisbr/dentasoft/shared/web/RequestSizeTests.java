package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.WithTestEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Against the real Tomcat, because MockMvc always knows the body length. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dentasoft.http.max-request-body-size=1KB")
@WithTestEnvironment
@Import(TestEndpoints.class)
class RequestSizeTests {

    private static final int LIMIT = 1024;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void bodyWithinTheLimitIsAccepted() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString(jsonOfSize(LIMIT)));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void declaredContentLengthOverTheLimitIsRejectedWith413() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString(jsonOfSize(LIMIT + 1)));

        assertIsProblem413(response);
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedWith413() throws Exception {
        byte[] body = jsonOfSize(LIMIT * 4).getBytes(StandardCharsets.UTF_8);

        // ofInputStream has no known length, so the client sends Transfer-Encoding: chunked.
        HttpResponse<String> response =
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));

        assertIsProblem413(response);
    }

    @Test
    void chunkedBodyWithinTheLimitIsAccepted() throws Exception {
        byte[] body = jsonOfSize(LIMIT / 2).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response =
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static void assertIsProblem413(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"status\":413")
                .contains("\"title\":\"Petición demasiado grande\"")
                .contains("\"detail\":\"El contenido de la petición supera el tamaño máximo permitido.\"");
    }

    private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/test/echo"))
                .header("Authorization", TestTokens.anyUser())
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** A JSON object of exactly {@code size} bytes. */
    private static String jsonOfSize(int size) {
        String prefix = "{\"text\":\"";
        String suffix = "\"}";
        return prefix + "x".repeat(size - prefix.length() - suffix.length()) + suffix;
    }
}
