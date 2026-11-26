package es.crisbr.dentasoft.identity.internal.keycloak;

import java.util.List;
import java.util.UUID;

/**
 * A user of the realm as the Admin API returns it. The name and the email live only in Keycloak (never in our
 * database or the logs), so {@link #toString()} prints the id and the flags only.
 *
 * @param id the Keycloak id, which is also the {@code sub} of the user's tokens and {@code user_account.id}
 * @param requiredActions what the user still has to do before signing in ({@code VERIFY_EMAIL}, {@code
 *     UPDATE_PASSWORD}, {@code CONFIGURE_TOTP}…)
 */
public record KeycloakUser(
        UUID id,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        List<String> requiredActions) {

    public KeycloakUser {
        requiredActions = List.copyOf(requiredActions);
    }

    /** The user has not finished the invitation yet: the email is not verified or some action is still required. */
    public boolean invitationPending() {
        return !emailVerified || !requiredActions.isEmpty();
    }

    @Override
    public String toString() {
        return "KeycloakUser[id=" + id + ", enabled=" + enabled + ", emailVerified=" + emailVerified
                + ", requiredActions=" + requiredActions + "]";
    }
}
