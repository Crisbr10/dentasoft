package es.crisbr.dentasoft.shared.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error raised by a controller into an RFC 9457 {@link ProblemDetail}.
 *
 * <p>Spring MVC exceptions are handled by {@link ResponseEntityExceptionHandler}. Anything else becomes a
 * generic problem whose texts come from {@code messages.properties}: exception messages and stack traces
 * never reach the client. Security exceptions (access denied, authentication) need their own handlers when
 * Spring Security is added, otherwise they would fall into the generic 500 handler.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One invalid field. Never carries the rejected value, which may be personal data. */
    record InvalidField(String field, @Nullable String message) {}

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toInvalidField)
                .toList();
        ex.getBody().setProperty("errors", errors);
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    /** A body without {@code Content-Length} that passed the size limit while it was being read. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        RequestBodyTooLargeException tooLarge = findCause(ex, RequestBodyTooLargeException.class);
        if (tooLarge != null) {
            return handleErrorResponseException(tooLarge, tooLarge.getHeaders(), tooLarge.getStatusCode(), request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @Nullable
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ProblemDetail body = createProblemDetail(
                ex, status, "Invalid request content.", "problemDetail.constraint-violation.detail", null, request);
        List<InvalidField> errors = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toInvalidField)
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        HttpStatus status = statusOf(ex);
        ProblemDetail body;
        if (status.is5xxServerError()) {
            // Messages are dropped: they may carry personal or clinical data. The request ID is in the MDC.
            log.error("Unhandled exception {}", ex.getClass().getName(), RedactedException.of(ex));
            body = ErrorResponse.builder(ex, status, "Unexpected error.")
                    .titleMessageCode("problemDetail.internal-error.title")
                    .detailMessageCode("problemDetail.internal-error.detail")
                    .build()
                    .updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        } else {
            // A @ResponseStatus exception keeps its status, but its message is internal: only the title is sent.
            body = ProblemDetail.forStatus(status);
        }
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    /** Walks the cause chain, stopping at a cycle (A caused by B caused by A). */
    static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    private static HttpStatus statusOf(Exception ex) {
        ResponseStatus annotation = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        return annotation != null ? annotation.code() : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static InvalidField toInvalidField(FieldError error) {
        return new InvalidField(error.getField(), error.getDefaultMessage());
    }

    private static InvalidField toInvalidField(ConstraintViolation<?> violation) {
        String leaf = null;
        for (Path.Node node : violation.getPropertyPath()) {
            leaf = node.getName();
        }
        return new InvalidField(leaf != null ? leaf : "", violation.getMessage());
    }
}
