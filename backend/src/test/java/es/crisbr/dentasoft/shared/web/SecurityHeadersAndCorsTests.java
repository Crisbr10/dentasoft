package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@WithTestEnvironment
@Import(TestEndpoints.class)
class SecurityHeadersAndCorsTests {

    private static final String OTHER_ORIGIN = "https://evil.dentasoft.test";

    @Autowired
    private MockMvcTester mvc;

    @Nested
    class SecurityHeaders {

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/health", "/api/v1/test/ping", "/api/v1/does-not-exist"})
        void areSentOnEveryResponse(String path) {
            MvcTestResult result = mvc.get().uri(path).exchange();

            assertThat(result)
                    .hasHeader("Content-Security-Policy", SecurityHeadersFilter.API_CSP)
                    .hasHeader("X-Content-Type-Options", "nosniff")
                    .hasHeader("X-Frame-Options", "DENY")
                    .hasHeader("Referrer-Policy", "no-referrer")
                    .hasHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
                    .hasHeader("Cross-Origin-Opener-Policy", "same-origin")
                    .hasHeader("Cross-Origin-Resource-Policy", "same-origin")
                    .hasHeader("Cache-Control", "no-store")
                    .containsHeader("Permissions-Policy");
        }
    }

    @Nested
    class RequestId {

        @Test
        void isGeneratedWhenTheClientSendsNone() {
            String id =
                    mvc.get().uri("/api/v1/test/ping").exchange().getResponse().getHeader("X-Request-Id");

            assertThat(id).isNotNull();
            assertThat(UUID.fromString(id)).isNotNull();
        }

        @Test
        void keepsAValidIdFromTheClient() {
            assertThat(mvc.get().uri("/api/v1/test/ping").header("X-Request-Id", "front-123_abc.Z9"))
                    .hasHeader("X-Request-Id", "front-123_abc.Z9");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "has space",
                    "line\r\nX-Injected: 1",
                    "ñandú",
                    "{\"json\":1}",
                    "${jndi:ldap://x}",
                    "0123456789012345678901234567890123456789012345678901234567890123456789"
                })
        void replacesAnInvalidIdInsteadOfEchoingIt(String invalid) {
            String id = mvc.get()
                    .uri("/api/v1/test/ping")
                    .header("X-Request-Id", invalid)
                    .exchange()
                    .getResponse()
                    .getHeader("X-Request-Id");

            assertThat(id).isNotEqualTo(invalid);
            assertThat(UUID.fromString(id)).isNotNull();
        }

        @Test
        void acceptsTheMaximumLength() {
            String longest = "a".repeat(RequestIdFilter.MAX_LENGTH);

            assertThat(mvc.get().uri("/api/v1/test/ping").header("X-Request-Id", longest))
                    .hasHeader("X-Request-Id", longest);
        }
    }

    @Nested
    class Cors {

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/api/v1/test/ping"})
        void preflightFromTheFrontendIsAllowedWithoutCredentials(String path) {
            MvcTestResult result = mvc.options()
                    .uri(path)
                    .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .header(
                            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                            "authorization, content-type, x-request-id, x-clinic-id")
                    .exchange();

            assertThat(result).hasStatusOk();
            assertThat(result)
                    .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WithTestEnvironment.APP)
                    .hasHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600")
                    .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsIgnoringCase("authorization")
                    .containsIgnoringCase("content-type")
                    .containsIgnoringCase("x-request-id")
                    .containsIgnoringCase("x-clinic-id");
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                    .isEqualTo("GET,POST,PUT");
        }

        /** Only the headers the API uses: no cookies, no forwarding headers from the browser. */
        @ParameterizedTest
        @ValueSource(strings = {"cookie", "x-forwarded-for"})
        void preflightWithAHeaderTheApiDoesNotUseIsRejected(String header) {
            assertThat(mvc.options()
                            .uri("/api/v1/test/ping")
                            .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, header))
                    .hasStatus(HttpStatus.FORBIDDEN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"PATCH", "DELETE"})
        void preflightWithAMethodTheApiDoesNotUseYetIsRejected(String method) {
            assertThat(mvc.options()
                            .uri("/api/v1/test/ping")
                            .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method))
                    .hasStatus(HttpStatus.FORBIDDEN);
        }

        /** The browser must be able to read the 401 to send the user to sign in again. */
        @Test
        void aRejectedRequestFromTheFrontendStillCarriesTheCorsHeaders() {
            assertThat(mvc.get().uri("/api/v1/test/ping").header(HttpHeaders.ORIGIN, WithTestEnvironment.APP))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WithTestEnvironment.APP)
                    .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        }

        @Test
        void preflightFromAnotherOriginIsRejected() {
            MvcTestResult result = mvc.options()
                    .uri("/api/v1/test/ping")
                    .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange();

            assertThat(result)
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        @Test
        void preflightWithAHeaderOutsideTheListIsRejected() {
            assertThat(mvc.options()
                            .uri("/api/v1/test/ping")
                            .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-something-else"))
                    .hasStatus(HttpStatus.FORBIDDEN);
        }

        @Test
        void actualRequestFromTheFrontendExposesRequestIdAndRetryAfter() {
            MvcTestResult result = mvc.get()
                    .uri("/actuator/health")
                    .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                    .exchange();

            assertThat(result)
                    .hasStatusOk()
                    .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WithTestEnvironment.APP);
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                    .contains("X-Request-Id")
                    .contains("Retry-After");
        }

        @Test
        void actualRequestFromAnotherOriginIsRejected() {
            assertThat(mvc.get().uri("/api/v1/test/ping").header(HttpHeaders.ORIGIN, OTHER_ORIGIN))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        @Test
        void pathsOutsideTheApiAndHealthHaveNoCors() {
            assertThat(mvc.options()
                            .uri("/actuator/info")
                            .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }
    }
}
