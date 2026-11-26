package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.WithTestEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On Kubernetes Spring Boot would turn on Tomcat's RemoteIpValve by itself, which trusts {@code X-Forwarded-For}
 * from private addresses and rewrites the remote address before {@link ClientIpResolver} sees it. With
 * {@code server.forward-headers-strategy: none} only the resolver decides. Against the real Tomcat, because MockMvc
 * has no valves.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.cloud-platform=kubernetes")
@WithTestEnvironment
@Import(ForwardedHeadersOnKubernetesTests.ClientIpEndpoint.class)
class ForwardedHeadersOnKubernetesTests {

    private static final String SPOOFED_IP = "203.0.113.7";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Test
    void aSpoofedForwardedForFromAnUntrustedAddressDoesNotChangeTheClientIp() throws Exception {
        assertThat(CloudPlatform.getActive(environment)).isEqualTo(CloudPlatform.KUBERNETES);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ClientIpEndpoint.PATH))
                        .header("Authorization", TestTokens.anyUser())
                        .header("X-Forwarded-For", SPOOFED_IP)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("127.0.0.1");
    }

    /** Returns the client IP as the rate limiter sees it. */
    @RestController
    static class ClientIpEndpoint {

        static final String PATH = "/api/v1/test/client-ip";

        private final ClientIpResolver resolver;

        ClientIpEndpoint(ClientIpResolver resolver) {
            this.resolver = resolver;
        }

        @GetMapping(PATH)
        String clientIp(HttpServletRequest request) {
            return resolver.resolve(request).getHostAddress();
        }
    }
}
