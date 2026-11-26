package es.crisbr.dentasoft.identity.internal.keycloak;

/**
 * Keycloak answered 409: the user or the organization already exists. A {@link KeycloakAdminException}, so catching
 * that also catches this. A user may exist without the service account seeing it (a platform administrator, outside
 * its group), so a conflict on creation does not mean the caller can find that user.
 */
public class KeycloakConflictException extends KeycloakAdminException {

    public KeycloakConflictException(String message) {
        super(message);
    }
}
