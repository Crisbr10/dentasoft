package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakConflictException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUser;
import es.crisbr.dentasoft.shared.Quotas;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The steps every invitation shares, whoever sends it: an administrator of the clinic ({@link ClinicUsers}) or the
 * platform, for the clinic's first administrator ({@link PlatformFirstAdminInvitees}). Both count against the same
 * quota of the clinic.
 */
final class Invitees {

    /** Invitations and resends a clinic may send per day. */
    static final int INVITATIONS_PER_DAY = 20;

    static final String INVITATION_QUOTA = "invitation";

    private static final Logger log = LoggerFactory.getLogger(Invitees.class);

    private Invitees() {}

    /** The Keycloak user to invite, and whether they still have to set up their account. */
    record Invitee(UUID id, boolean pendingSetup) {}

    /** @throws InvitationLimitExceededException if {@code clinic} has used its invitations for now */
    static void requireQuota(Quotas quotas, UUID clinic) {
        Quotas.Decision decision =
                quotas.tryAcquire(INVITATION_QUOTA, clinic.toString(), INVITATIONS_PER_DAY, Duration.ofDays(1));
        if (!decision.allowed()) {
            log.info("Clinic {} reached its limit of invitations", clinic);
            throw new InvitationLimitExceededException(decision.retryAfter());
        }
    }

    /**
     * The person's Keycloak user, found by email or created in group {@code clinic-users} without a password; empty if
     * the email belongs to a user Dentasoft cannot see (outside {@code clinic-users}, such as a platform
     * administrator). Keycloak's exceptions propagate: each caller turns them into its own answer.
     */
    static Optional<Invitee> findOrCreate(KeycloakAdmin keycloak, String email, String firstName, String lastName) {
        Optional<KeycloakUser> existing = keycloak.findUserByEmail(email);
        if (existing.isEmpty()) {
            try {
                UUID created = keycloak.createUser(email, firstName, lastName);
                return Optional.of(new Invitee(created, true));
            } catch (KeycloakConflictException taken) {
                // Created meanwhile by another request, or a user outside clinic-users: look again.
                existing = keycloak.findUserByEmail(email);
            }
        }
        return existing.map(user -> new Invitee(user.id(), user.invitationPending()));
    }
}
