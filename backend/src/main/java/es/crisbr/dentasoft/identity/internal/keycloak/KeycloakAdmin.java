package es.crisbr.dentasoft.identity.internal.keycloak;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The calls the backend makes to the Keycloak Admin REST API, with the service account of {@code dentasoft-backend}.
 * Its fine-grained permissions (FGAP v2) only let it see and create users of the {@code clinic-users} group, so a
 * platform administrator is invisible to it: searches do not find them and reading them answers 403.
 *
 * <p>Calls are never made inside a database transaction. Every method throws {@link KeycloakUnavailableException} when
 * Keycloak answers 5xx, cannot be reached or does not answer in time, and {@link KeycloakAdminException} on any other
 * unexpected answer.
 */
public interface KeycloakAdmin {

    /** The required actions of every invitation: verify the email, set a password and configure the OTP. */
    List<String> INVITATION_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");

    /** The only group the service account may create users into. */
    String CLINIC_USERS_GROUP = "/clinic-users";

    /** The user with exactly this email, if the service account can see it. */
    Optional<KeycloakUser> findUserByEmail(String email);

    /** The user, or empty if it does not exist or the service account cannot see it. */
    Optional<KeycloakUser> findUser(UUID id);

    /**
     * Creates an enabled user in {@link #CLINIC_USERS_GROUP} with an unverified email, no password and the {@link
     * #INVITATION_ACTIONS} pending. The username is the email.
     *
     * @return the id Keycloak gave it
     * @throws KeycloakConflictException if a user with that email or username exists, visible or not
     */
    UUID createUser(String email, String firstName, String lastName);

    /**
     * Asks Keycloak to email the user a link to complete the {@link #INVITATION_ACTIONS}, valid for {@code
     * dentasoft.keycloak-admin.invitation-lifespan}, that returns to the frontend ({@code dentasoft.urls.app}).
     */
    void sendActionsEmail(UUID userId);

    /** Ends every session of the user. */
    void logout(UUID userId);

    /**
     * The organization of the clinic, created if missing. Its name and alias are both the clinic id. Two concurrent
     * calls for the same clinic return the same organization.
     *
     * @return the Keycloak id of the organization
     */
    String ensureOrganization(UUID clinicId);

    /** Makes the user a member of the organization; nothing happens if it already is. */
    void addOrganizationMember(String organizationId, UUID userId);

    /** Removes the user from the organization; nothing happens if it was not a member. */
    void removeOrganizationMember(String organizationId, UUID userId);

    /** Whether the user is a member of any organization, that is, of any clinic. */
    boolean belongsToAnyOrganization(UUID userId);
}
