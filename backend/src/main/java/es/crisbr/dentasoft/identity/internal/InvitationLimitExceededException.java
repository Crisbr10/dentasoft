package es.crisbr.dentasoft.identity.internal;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * The active clinic has sent all the invitations it may send for now (invitations and resends count together): 429
 * with {@code Retry-After}. Keeps a stolen administrator account from using Dentasoft to send mass email.
 */
public class InvitationLimitExceededException extends ErrorResponseException {

    InvitationLimitExceededException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())));
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.invitation-limit.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.invitation-limit.detail";
    }
}
