package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.identity.FirstAdminInvitees;
import es.crisbr.dentasoft.identity.FirstAdminInvitees.Invitee;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The platform invites the first administrator of a clinic that has none (M02-T11, ADR 0018): an administrator of the
 * clinic invites everyone else (M02-T05), but a new clinic has nobody to do it. The platform can also send the
 * invitation again, or cancel it, while it is pending.
 *
 * <p>The membership is written by the platform's own database role ({@link FirstAdminMemberships}); Keycloak is asked
 * through {@code identity}'s public API ({@link FirstAdminInvitees}), never inside a transaction, in the same order as
 * an administrator's invitation: a failure at any step leaves nothing that repeating the request does not complete.
 */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class FirstAdminInvitations {

    private final FirstAdminMemberships memberships;
    private final FirstAdminInvitees invitees;

    FirstAdminInvitations(FirstAdminMemberships memberships, FirstAdminInvitees invitees) {
        this.memberships = memberships;
        this.invitees = invitees;
    }

    /** Whether the clinic has an administrator, accepted or invited. */
    public enum AdminInvitationState {
        /** No administrator and no pending invitation: the platform may invite one. */
        NONE,
        /** An invitation the person has not accepted yet: the platform may resend or cancel it. */
        PENDING,
        /** The clinic has an administrator, who invites everyone else: the platform is out of it. */
        ACCEPTED
    }

    /**
     * What the platform sees of the invitation: never who it was sent to. {@code invitedAt} is set while it is pending
     * ({@code updated_at}, V013); {@code acceptedAt}, once accepted.
     */
    public record AdminInvitationStatus(
            AdminInvitationState status,
            @Nullable Instant invitedAt,
            @Nullable Instant acceptedAt) {}

    /** What the platform administrator types; already validated by the web layer. */
    public record NewAdministrator(String email, String firstName, String lastName) {

        /** Never prints the email or the name, should it end up in a log. */
        @Override
        public String toString() {
            return "NewAdministrator[…]";
        }
    }

    /**
     * Whether the clinic has an administrator, a pending invitation or neither, and when; never who it is.
     *
     * @throws ClinicNotFoundException 404 if there is no such clinic
     */
    public AdminInvitationStatus status(UUID clinicId) {
        return memberships.status(clinicId);
    }

    /**
     * Invites the person as the clinic's first administrator. The answer is the same whether Keycloak already knew
     * them or not, and when the email belongs to someone Dentasoft cannot see (another platform administrator), in
     * which case nothing is done.
     *
     * <ol>
     *   <li>The clinic must exist and have no active administrator, accepted or pending.
     *   <li>Their Keycloak user is found by email or created (this counts against the clinic's invitations).
     *   <li>One transaction creates their account if missing and a pending membership, recorded in the audit log.
     *       PostgreSQL refuses it if the clinic got an administrator meanwhile.
     *   <li>After it commits, Keycloak emails them the link to set up their account, if they have not yet.
     * </ol>
     *
     * A failure in step 2 or 3 is an error answer, and repeating the request finishes the work: the user is found by
     * email, and the account is not created twice. A failure in step 4 leaves the invitation in place;
     * {@link #resend} sends the email again.
     *
     * @throws ClinicNotFoundException 404 if there is no such clinic
     * @throws FirstAdminConflictException 409 if the clinic has an administrator or a pending invitation of one
     */
    public void invite(UUID clinicId, NewAdministrator administrator) {
        memberships.requireNoAdministrator(clinicId);
        Optional<Invitee> invitee = invitees.findOrCreate(
                clinicId, administrator.email(), administrator.firstName(), administrator.lastName());
        if (invitee.isEmpty()) {
            return;
        }
        memberships.invite(clinicId, invitee.get().userId());
        invitees.sendInvitationEmail(invitee.get());
    }

    /**
     * Sends the pending invitation's email again (the link expired, or the email was lost), and records it. Someone
     * who already had a Dentasoft account gets no email, with the same answer.
     *
     * @throws ClinicNotFoundException 404 if there is no such clinic
     * @throws FirstAdminInvitationNotFoundException 404 if it has no pending invitation of an administrator
     */
    public void resend(UUID clinicId) {
        FirstAdminMemberships.PendingInvitation pending = memberships.pendingInvitation(clinicId);
        invitees.resendInvitationEmail(clinicId, pending.userId());
        memberships.recordResend(clinicId, pending.membershipId());
    }

    /**
     * Cancels the pending invitation; another person can then be invited.
     *
     * @throws ClinicNotFoundException 404 if there is no such clinic
     * @throws FirstAdminInvitationNotFoundException 404 if it has no pending invitation of an administrator
     */
    public void cancel(UUID clinicId) {
        memberships.cancel(clinicId);
    }
}
