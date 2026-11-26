package es.crisbr.dentasoft.identity.internal.keycloak;

import org.jspecify.annotations.Nullable;

/**
 * Keycloak could not serve the call now: it answered 5xx, refused the connection or did not answer in time. Trying
 * again later may work. Not a {@link KeycloakAdminException}, so callers can tell an outage from a refusal; the
 * message never carries personal data or a response body either.
 */
public class KeycloakUnavailableException extends RuntimeException {

    public KeycloakUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
