package es.crisbr.dentasoft.identity.internal.keycloak;

import org.jspecify.annotations.Nullable;

/**
 * Keycloak answered an Admin API call in a way the caller did not expect. The message names the operation and the
 * HTTP status only: never the email, the names or the response body, which may repeat them. The cause, if any, is a
 * copy without messages ({@code Logging.redacted}).
 */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
