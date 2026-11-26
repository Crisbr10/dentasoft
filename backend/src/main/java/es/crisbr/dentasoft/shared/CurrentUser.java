package es.crisbr.dentasoft.shared;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The user behind the current request: the Keycloak user ID ({@code sub} of the access token), which the audit
 * columns and the audit log record.
 *
 * <p>Empty without an authenticated user: scheduled jobs, asynchronous listeners and anonymous requests act as the
 * system. Any other kind of authentication is refused instead of being taken for the system, so a change a person
 * made is never recorded as made by nobody. The token's {@code sub} is always a UUID: the decoder rejects tokens
 * whose {@code sub} is not ({@code identity} module).
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(UUID.fromString(token.getToken().getSubject()));
        }
        throw new IllegalStateException(
                "Unsupported authentication " + authentication.getClass().getName());
    }
}
