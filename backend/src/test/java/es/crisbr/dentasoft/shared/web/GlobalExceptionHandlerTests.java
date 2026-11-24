package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import(GlobalExceptionHandlerTests.FailingController.class)
class GlobalExceptionHandlerTests {

    private static final String INTERNAL_MESSAGE = "internal detail that must not leak";

    private static final String REJECTED_VALUE = "rejected-value-that-must-not-leak";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void unexpectedErrorReturnsGenericProblemDetailWithoutInternals() {
        assertThat(mvc.get().uri("/test/unexpected"))
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

    @Test
    void exceptionAnnotatedWithResponseStatusKeepsItsStatus() {
        assertThat(mvc.get().uri("/test/conflict"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_MESSAGE));
    }

    @Test
    void invalidRequestBodyReturnsProblemDetailWithFieldErrors() {
        assertThat(mvc.post()
                        .uri("/test/echo")
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
        assertThat(mvc.get().uri("/test/constraint"))
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
        assertThat(mvc.get().uri("/test/constraint-violations"))
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
        assertThat(mvc.get().uri("/test/does-not-exist"))
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

        @PostMapping("/test/echo")
        EchoRequest echo(@Valid @RequestBody EchoRequest request) {
            return request;
        }
    }
}
