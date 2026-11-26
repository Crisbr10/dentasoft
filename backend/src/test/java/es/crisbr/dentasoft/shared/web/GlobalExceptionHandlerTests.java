package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithJwtUser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Only this controller: the slice does not load the services other controllers need.
@WebMvcTest(controllers = GlobalExceptionHandlerTests.FailingController.class)
@Import({
    GlobalExceptionHandlerTests.FailingController.class,
    SecurityConfiguration.class,
    GlobalExceptionHandlerTests.AnyTokenIsValid.class
})
class GlobalExceptionHandlerTests {

    /** Any token is valid here ({@link AnyTokenIsValid}): these tests are about the errors after authentication. */
    private static final String SIGNED_IN = "Bearer test-token";

    private static final String INTERNAL_MESSAGE = "internal detail that must not leak";

    private static final String REJECTED_VALUE = "rejected-value-that-must-not-leak";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void unexpectedErrorReturnsGenericProblemDetailWithoutInternals() {
        assertThat(mvc.get().uri("/test/unexpected").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(INTERNAL_MESSAGE)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("at es.crisbr"))
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo(500);
                    assertThat(json).extractingPath("$.title").isEqualTo("Error interno");
                    assertThat(json)
                            .extractingPath("$.detail")
                            .isEqualTo("Se ha producido un error inesperado. Inténtalo de nuevo más tarde.");
                });
    }

    /** What method security ({@code @PreAuthorize}) throws for an authenticated user it refuses. */
    @Test
    void accessDeniedReturnsForbiddenProblemDetailInsteadOfAnInternalError() {
        assertThat(mvc.get().uri("/test/access-denied").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_MESSAGE))
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Acceso denegado");
                    assertThat(json)
                            .extractingPath("$.detail")
                            .isEqualTo("No tienes permiso para realizar esta operación.");
                });
    }

    /** What method security throws when nobody is authenticated. */
    @Test
    void missingAuthenticationReturnsUnauthorizedProblemDetail() {
        assertThat(mvc.get().uri("/test/unauthenticated").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_MESSAGE))
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Autenticación necesaria");
    }

    @Test
    void exceptionAnnotatedWithResponseStatusKeepsItsStatus() {
        assertThat(mvc.get().uri("/test/conflict").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_MESSAGE));
    }

    @Test
    void invalidRequestBodyReturnsProblemDetailWithFieldErrors() {
        assertThat(mvc.post()
                        .uri("/test/echo")
                        .header(HttpHeaders.AUTHORIZATION, SIGNED_IN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo(400);
                    assertThat(json).extractingPath("$.errors[0].field").isEqualTo("name");
                    assertThat(json)
                            .extractingPath("$.errors[0].message")
                            .asString()
                            .isNotBlank();
                });
    }

    @Test
    void constraintViolationReturnsBadRequestProblemDetail() {
        assertThat(mvc.get().uri("/test/constraint").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_MESSAGE))
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("La petición contiene datos no válidos.");
    }

    @Test
    void realConstraintViolationsNameTheLeafFieldAndNeverTheRejectedValue() {
        assertThat(mvc.get().uri("/test/constraint-violations").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(REJECTED_VALUE))
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.errors.length()").isEqualTo(1);
                    assertThat(json).extractingPath("$.errors[0].field").isEqualTo("phone");
                    assertThat(json)
                            .extractingPath("$.errors[0].message")
                            .asString()
                            .isNotBlank();
                });
    }

    @Test
    void unknownPathReturnsNotFoundProblemDetail() {
        assertThat(mvc.get().uri("/test/does-not-exist").header(HttpHeaders.AUTHORIZATION, SIGNED_IN))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(404);
    }

    record EchoRequest(@NotBlank String name) {}

    record Contact(@Size(max = 9) String phone) {}

    record Person(@Valid Contact contact) {}

    @ResponseStatus(HttpStatus.CONFLICT)
    static class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    @RestController
    static class FailingController {

        private final Validator validator;

        FailingController(Validator validator) {
            this.validator = validator;
        }

        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException(INTERNAL_MESSAGE);
        }

        @GetMapping("/test/conflict")
        String conflict() {
            throw new ConflictException(INTERNAL_MESSAGE);
        }

        @GetMapping("/test/constraint")
        String constraint() {
            throw new ConstraintViolationException(INTERNAL_MESSAGE, Set.of());
        }

        /** Path {@code contact.phone}: the response must keep only {@code phone}. */
        @GetMapping("/test/constraint-violations")
        String constraintViolations() {
            Set<ConstraintViolation<Person>> violations = validator.validate(new Person(new Contact(REJECTED_VALUE)));
            throw new ConstraintViolationException(violations);
        }

        @GetMapping("/test/access-denied")
        String accessDenied() {
            throw new AccessDeniedException(INTERNAL_MESSAGE);
        }

        @GetMapping("/test/unauthenticated")
        String unauthenticated() {
            throw new AuthenticationCredentialsNotFoundException(INTERNAL_MESSAGE);
        }

        @PostMapping("/test/echo")
        EchoRequest echo(@Valid @RequestBody EchoRequest request) {
            return request;
        }
    }

    /** The slice has no {@code identity} module: a decoder that accepts any token as a signed-in user. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AnyTokenIsValid {

        @Bean
        JwtDecoder anyTokenIsValid() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(WithJwtUser.DEFAULT_SUB)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }
    }
}
