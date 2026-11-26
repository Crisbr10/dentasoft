package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import es.crisbr.dentasoft.identity.persistence.ClinicMembershipRepository;
import es.crisbr.dentasoft.shared.CurrentUser;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction that accepts an invitation, run with the invitation's clinic active ({@link MyClinics#accept}):
 * only there does Row Level Security let the membership be changed, and V009 checks that it is the user's own.
 */
@Service
@PreAuthorize("isAuthenticated()")
class InvitationAcceptance {

    private final ClinicMembershipRepository memberships;

    InvitationAcceptance(ClinicMembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** @throws MembershipNotFoundException unless it is a pending, active invitation of the signed-in user */
    @Transactional
    void accept(UUID membershipId) {
        UUID user = CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user"));
        ClinicMembership membership = memberships
                .findById(membershipId)
                .filter(found -> found.getUserId().equals(user) && found.isActive() && !found.isAccepted())
                .orElseThrow(MembershipNotFoundException::new);
        membership.accept(OffsetDateTime.now());
        memberships.flush();
    }
}
