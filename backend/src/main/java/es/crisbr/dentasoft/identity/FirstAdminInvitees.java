package es.crisbr.dentasoft.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * The Keycloak side of inviting a clinic's first administrator, for the platform module (M02-T11, ADR 0018), which
 * writes the membership itself with its own database role. Only for {@code PLATFORM_ADMIN}. No method runs inside a
 * transaction or opens one: call them before or after the platform's transactions.
 *
 * <p>The name and email typed by the platform administrator go to Keycloak only, never to the database or the log.
 * Invitations and resends count against the clinic's daily quota of invitations, the same one its administrators use.
 */
public interface FirstAdminInvitees {

    /**
     * The person's Keycloak user, found by email or created in group {@code clinic-users} without a password. Empty if
     * the email belongs to a user Dentasoft cannot see (a platform administrator): then nothing must be done, with the
     * same answer as if it had been, so it reveals nothing.
     *
     * @throws org.springframework.web.ErrorResponseException 429 if the clinic has used its invitations for now, 503 if
     *     Keycloak fails
     */
    Optional<Invitee> findOrCreate(UUID clinicId, String email, String firstName, String lastName);

    /**
     * Emails {@code invitee} the link to set up their account, if they have not yet. A failure is logged, not thrown:
     * the membership already exists, and {@link #resendInvitationEmail} sends it again.
     */
    void sendInvitationEmail(Invitee invitee);

    /**
     * Sends the invitation email again, if the user has not set up their account yet; someone who already had one gets
     * nothing, with the same answer.
     *
     * @throws org.springframework.web.ErrorResponseException 429 if the clinic has used its invitations for now, 503 if
     *     Keycloak fails
     */
    void resendInvitationEmail(UUID clinicId, UUID userId);

    /** The person's Keycloak user ({@code sub}), and whether they still have to set up their account. */
    record Invitee(UUID userId, boolean setupPending) {}
}
