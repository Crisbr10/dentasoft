package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.FirstAdminInvitees;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdminException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUnavailableException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUser;
import es.crisbr.dentasoft.shared.Logging;
import es.crisbr.dentasoft.shared.Quotas;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** @see FirstAdminInvitees */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class PlatformFirstAdminInvitees implements FirstAdminInvitees {

    private static final Logger log = LoggerFactory.getLogger(PlatformFirstAdminInvitees.class);

    private final KeycloakAdmin keycloak;
    private final Quotas quotas;

    PlatformFirstAdminInvitees(KeycloakAdmin keycloak, Quotas quotas) {
        this.keycloak = keycloak;
        this.quotas = quotas;
    }

    @Override
    public Optional<Invitee> findOrCreate(UUID clinicId, String email, String firstName, String lastName) {
        Invitees.requireQuota(quotas, clinicId);
        try {
            Optional<Invitee> invitee = Invitees.findOrCreate(keycloak, email, firstName, lastName)
                    .map(found -> new Invitee(found.id(), found.pendingSetup()));
            if (invitee.isEmpty()) {
                log.info(
                        "First administrator of clinic {} not invited: the email belongs to a user outside the clinic"
                                + " users",
                        clinicId);
            }
            return invitee;
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            throw unavailable(clinicId, failure);
        }
    }

    @Override
    public void sendInvitationEmail(Invitee invitee) {
        if (!invitee.setupPending()) {
            return;
        }
        try {
            keycloak.sendActionsEmail(invitee.userId());
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            log.warn(
                    "Invitation email for user {} not sent; it can be sent again",
                    invitee.userId(),
                    Logging.redacted(failure));
        }
    }

    @Override
    public void resendInvitationEmail(UUID clinicId, UUID userId) {
        Invitees.requireQuota(quotas, clinicId);
        try {
            boolean setupPending = keycloak.findUser(userId)
                    .map(KeycloakUser::invitationPending)
                    .orElse(false);
            if (setupPending) {
                keycloak.sendActionsEmail(userId);
            }
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            throw unavailable(clinicId, failure);
        }
    }

    private static IdentityProviderUnavailableException unavailable(UUID clinicId, RuntimeException failure) {
        log.warn(
                "Keycloak failed while inviting the first administrator of clinic {}",
                clinicId,
                Logging.redacted(failure));
        return new IdentityProviderUnavailableException();
    }
}
