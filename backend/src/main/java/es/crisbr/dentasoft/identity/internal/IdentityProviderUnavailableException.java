package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Keycloak did not answer, or answered with an error, so the operation was not done (or not completely): 503. Repeating it is safe: every step is idempotent. */
public class IdentityProviderUnavailableException extends ErrorResponseException {

    IdentityProviderUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.identity-provider-unavailable.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.identity-provider-unavailable.detail";
    }
}
