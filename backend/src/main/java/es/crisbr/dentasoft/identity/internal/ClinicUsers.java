package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdminException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakConflictException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUnavailableException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUser;
import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import es.crisbr.dentasoft.shared.Logging;
import es.crisbr.dentasoft.shared.Quotas;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The users of the active clinic, which {@code ADMIN_CLINICA} manages (M02-T05, ADR 0016): inviting people, sending
 * an invitation again, changing roles and deactivating memberships.
 *
 * <p>The database decides who belongs to the clinic and with which role (ADR 0015); Keycloak holds each person's name,
 * email and credentials, and a mirror of the memberships in its organizations. No call to Keycloak runs inside a
 * transaction: each operation calls it before or after the transactions of {@link ClinicMemberships}, in an order
 * where a failure at any step leaves nothing that a repeated request does not complete or reuse.
 *
 * <p>The name and email an administrator types go to Keycloak only: never to the database or the log. Logs and audit
 * hold ids.
 */
@Service
@PreAuthorize("hasRole('ADMIN_CLINICA')")
public class ClinicUsers {

    /** Invitations and resends a clinic may send per day. */
    static final int INVITATIONS_PER_DAY = 20;

    static final String INVITATION_QUOTA = "invitation";

    private static final Logger log = LoggerFactory.getLogger(ClinicUsers.class);

    private final ClinicMemberships memberships;
    private final KeycloakAdmin keycloak;
    private final Quotas quotas;

    ClinicUsers(ClinicMemberships memberships, KeycloakAdmin keycloak, Quotas quotas) {
        this.memberships = memberships;
        this.keycloak = keycloak;
        this.quotas = quotas;
    }

    /** What an administrator types to invite someone. */
    public record Invitation(String email, String firstName, String lastName, ClinicRole role) {

        /** Never prints the email or the name, should an invitation end up in a log. */
        @Override
        public String toString() {
            return "Invitation[role=" + role + "]";
        }
    }

    /**
     * One page of the clinic's users, active or not. Names and emails come from Keycloak, one request per user; if
     * Keycloak does not answer, the page still comes, with {@code profile} empty from then on.
     */
    public Page<ClinicUserDetails> list(Pageable pageable) {
        Page<ClinicMembership> page = memberships.list(pageable);
        List<ClinicUserDetails> users = new ArrayList<>(page.getNumberOfElements());
        boolean keycloakAnswers = true;
        for (ClinicMembership membership : page) {
            ClinicUserDetails.@Nullable Profile profile = null;
            if (keycloakAnswers) {
                try {
                    profile = profileOf(membership);
                } catch (KeycloakUnavailableException | KeycloakAdminException unavailable) {
                    keycloakAnswers = false;
                    log.warn(
                            "Keycloak did not answer while listing the users of clinic {}",
                            TenantContext.current(),
                            Logging.redacted(unavailable));
                }
            }
            users.add(ClinicUserDetails.of(membership, profile));
        }
        return new PageImpl<>(users, page.getPageable(), page.getTotalElements());
    }

    /**
     * Invites someone to the active clinic with a role. The answer is the same whether Keycloak already knew the
     * person (they work in another clinic) or not, so it reveals nothing about other clinics.
     *
     * <ol>
     *   <li>Their Keycloak user is looked up by email, or created in group {@code clinic-users}, without a password:
     *       they set it themselves. Nothing is written in the database yet.
     *   <li>One transaction creates their account if missing and gives them an active membership, pending until they
     *       accept it ({@code POST /api/v1/me/invitations/{id}/acceptance}): until then it grants nothing.
     *   <li>After it commits, if they have not set up their account yet, Keycloak emails them the link to verify the
     *       address, choose a password and set up the one-time code, which expires.
     * </ol>
     *
     * A failure in step 1 or 2 is a 503 or 409 and repeating the request finishes the work: the user is found by
     * email, and the account insert does nothing if it exists. A failure in step 3 leaves the membership in place;
     * {@link #resendInvitation} sends the email again. The person joins the clinic's Keycloak organization only when
     * they accept ({@link MyClinics#accept}): a pending invitation is not one of their clinics.
     *
     * <p>An email that belongs to someone outside {@code clinic-users} (a platform administrator, ADR 0016) is invisible
     * to Dentasoft's Keycloak account: creating the user fails and nothing is done, with the same answer. Platform
     * administrators use a separate account to work in a clinic.
     *
     * @throws InvitationLimitExceededException if the clinic has used its invitations for now
     * @throws AlreadyMemberException if the person is already an active user of the clinic
     * @throws IdentityProviderUnavailableException if Keycloak fails in step 1
     */
    public void invite(Invitation invitation) {
        UUID clinic = TenantContext.current();
        requireInvitationQuota(clinic);
        Optional<Invitee> invitee = findOrCreate(invitation);
        if (invitee.isEmpty()) {
            log.info("Invitation to clinic {} not sent: the email belongs to a user outside the clinic users", clinic);
            return;
        }
        ClinicMembership membership = memberships.addOrReactivate(invitee.get().id(), invitation.role());
        if (invitee.get().pendingSetup()) {
            sendActionsEmail(membership);
        }
    }

    /**
     * Sends the invitation email again, while the invitation is pending (the link expired, or the email was lost).
     * Keycloak only emails someone who has not set up their account yet; someone who already had one (from another
     * clinic) gets nothing, but the answer is the same, so it reveals nothing about other clinics. Counts against the
     * same quota as invitations.
     *
     * @throws MembershipNotFoundException if the active clinic has no membership with {@code membershipId}
     * @throws MembershipInactiveException if it is inactive
     * @throws InvitationNotPendingException if the person has already accepted the invitation
     * @throws InvitationLimitExceededException if the clinic has used its invitations for now
     * @throws IdentityProviderUnavailableException if Keycloak fails
     */
    public void resendInvitation(UUID membershipId) {
        ClinicMembership membership = memberships.get(membershipId);
        if (!membership.isActive()) {
            throw new MembershipInactiveException();
        }
        if (membership.isAccepted()) {
            throw new InvitationNotPendingException();
        }
        requireInvitationQuota(TenantContext.current());
        try {
            boolean setupPending = keycloak.findUser(membership.getUserId())
                    .map(KeycloakUser::invitationPending)
                    .orElse(false);
            if (setupPending) {
                keycloak.sendActionsEmail(membership.getUserId());
            }
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            throw unavailable(failure);
        }
    }

    /** @see ClinicMemberships#changeRole */
    public ClinicUserDetails changeRole(UUID membershipId, ClinicRole role, long version) {
        ClinicMembership membership = memberships.changeRole(membershipId, role, version);
        return ClinicUserDetails.of(membership, profileOrNull(membership));
    }

    /**
     * Deactivates the membership: the user loses access to the clinic on their next request (M02-T04). Then, after
     * the transaction, the user leaves the clinic's Keycloak organization and, if they belong to no other one, their
     * Keycloak sessions are closed. If Keycloak fails there, the deactivation stands and repeating the request
     * completes the rest: deactivating an inactive membership changes nothing in the database.
     *
     * @see ClinicMemberships#deactivate
     */
    public ClinicUserDetails deactivate(UUID membershipId, long version) {
        ClinicMembership membership = memberships.deactivate(membershipId, version);
        UUID userId = membership.getUserId();
        try {
            keycloak.removeOrganizationMember(keycloak.ensureOrganization(TenantContext.current()), userId);
            if (!keycloak.belongsToAnyOrganization(userId)) {
                keycloak.logout(userId);
            }
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            log.warn(
                    "Membership {} deactivated, but Keycloak was not updated; repeating the deactivation completes it",
                    membership.getId(),
                    Logging.redacted(failure));
        }
        return ClinicUserDetails.of(membership, profileOrNull(membership));
    }

    private void requireInvitationQuota(UUID clinic) {
        Quotas.Decision decision =
                quotas.tryAcquire(INVITATION_QUOTA, clinic.toString(), INVITATIONS_PER_DAY, Duration.ofDays(1));
        if (!decision.allowed()) {
            log.info("Clinic {} reached its limit of invitations", clinic);
            throw new InvitationLimitExceededException(decision.retryAfter());
        }
    }

    /** The Keycloak user to invite, and whether they still have to set up their account. */
    private record Invitee(UUID id, boolean pendingSetup) {}

    /** The person's Keycloak user, found or created; empty if the email belongs to a user Dentasoft cannot see. */
    private Optional<Invitee> findOrCreate(Invitation invitation) {
        try {
            Optional<KeycloakUser> existing = keycloak.findUserByEmail(invitation.email());
            if (existing.isEmpty()) {
                try {
                    UUID created =
                            keycloak.createUser(invitation.email(), invitation.firstName(), invitation.lastName());
                    return Optional.of(new Invitee(created, true));
                } catch (KeycloakConflictException taken) {
                    // Created meanwhile by another request, or a user outside clinic-users: look again.
                    existing = keycloak.findUserByEmail(invitation.email());
                }
            }
            return existing.map(user -> new Invitee(user.id(), user.invitationPending()));
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            throw unavailable(failure);
        }
    }

    private void sendActionsEmail(ClinicMembership membership) {
        try {
            keycloak.sendActionsEmail(membership.getUserId());
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            log.warn(
                    "Invitation email for membership {} not sent; it can be sent again",
                    membership.getId(),
                    Logging.redacted(failure));
        }
    }

    private ClinicUserDetails.@Nullable Profile profileOrNull(ClinicMembership membership) {
        try {
            return profileOf(membership);
        } catch (KeycloakUnavailableException | KeycloakAdminException unavailable) {
            log.warn("Keycloak did not answer for membership {}", membership.getId(), Logging.redacted(unavailable));
            return null;
        }
    }

    private ClinicUserDetails.@Nullable Profile profileOf(ClinicMembership membership) {
        return keycloak.findUser(membership.getUserId())
                .map(user -> ClinicUserDetails.Profile.of(user, membership.isAccepted()))
                .orElse(null);
    }

    private static IdentityProviderUnavailableException unavailable(RuntimeException failure) {
        log.warn(
                "Keycloak failed while managing the users of clinic {}",
                TenantContext.current(),
                Logging.redacted(failure));
        return new IdentityProviderUnavailableException();
    }
}
