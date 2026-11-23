package es.crisbr.dentasoft.shared.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The client has exceeded its request rate limit: 429 with {@code Retry-After}. */
class RateLimitExceededException extends ErrorResponseException {

    RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.rate-limit.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.rate-limit.detail";
    }
}
