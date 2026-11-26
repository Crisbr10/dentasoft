package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.UserAccounts.ActiveMembership;
import es.crisbr.dentasoft.identity.internal.UserAccounts.PendingInvitation;
import es.crisbr.dentasoft.tenancy.ClinicDirectory;
import es.crisbr.dentasoft.tenancy.ClinicDirectory.ClinicSummary;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clinics of the signed-in user, with their role in each, and their pending invitations: only their own
 * memberships, never anyone else's
 * (Row Level Security, V007). A suspended clinic is listed, marked as such, so the user knows why they cannot choose
 * it. Without active memberships, or with a disabled account, the list is empty: the user cannot work in any clinic.
 */
@Service
@PreAuthorize("isAuthenticated()")
public class MyClinics {

    private static final Comparator<MyClinic> BY_NAME =
            Comparator.comparing(MyClinic::name).thenComparing(MyClinic::clinicId);

    private final UserAccounts accounts;
    private final ClinicDirectory directory;
    private final InvitationAcceptance acceptance;

    MyClinics(UserAccounts accounts, ClinicDirectory directory, InvitationAcceptance acceptance) {
        this.accounts = accounts;
        this.directory = directory;
        this.acceptance = acceptance;
    }

    public record MyClinic(UUID clinicId, String name, ClinicRole role, boolean suspended) {}

    /** An invitation to a clinic that the user has not accepted yet: {@code id} is what {@link #accept} takes. */
    public record MyInvitation(UUID id, UUID clinicId, String name, ClinicRole role) {}

    /** The user's pending invitations, sorted by clinic name. */
    @Transactional(readOnly = true)
    public List<MyInvitation> invitationsOfCurrentUser() {
        List<PendingInvitation> invitations = accounts.pendingInvitationsOfCurrentUser();
        Map<UUID, ClinicSummary> clinics =
                directory
                        .visibleClinics(invitations.stream()
                                .map(PendingInvitation::clinicId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ClinicSummary::id, Function.identity()));
        return invitations.stream()
                .filter(invitation -> clinics.containsKey(invitation.clinicId()))
                .map(invitation -> new MyInvitation(
                        invitation.membershipId(),
                        invitation.clinicId(),
                        clinics.get(invitation.clinicId()).name(),
                        invitation.role()))
                .sorted(Comparator.comparing(MyInvitation::name).thenComparing(MyInvitation::clinicId))
                .toList();
    }

    /**
     * The user accepts one of their invitations: from the next request, the clinic is one of theirs. Outside any
     * transaction, because the change runs with the invitation's clinic active.
     *
     * @throws MembershipNotFoundException unless it is one of the user's pending invitations
     */
    public void accept(UUID invitationId) {
        UUID clinic = accounts.pendingInvitationsOfCurrentUser().stream()
                .filter(invitation -> invitation.membershipId().equals(invitationId))
                .map(PendingInvitation::clinicId)
                .findFirst()
                .orElseThrow(MembershipNotFoundException::new);
        TenantContext.runAs(clinic, () -> acceptance.accept(invitationId));
    }

    /** Sorted by name. */
    @Transactional(readOnly = true)
    public List<MyClinic> ofCurrentUser() {
        List<ActiveMembership> memberships = accounts.activeMembershipsOfCurrentUser();
        Map<UUID, ClinicSummary> clinics =
                directory
                        .visibleClinics(memberships.stream()
                                .map(ActiveMembership::clinicId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ClinicSummary::id, Function.identity()));
        return memberships.stream()
                .filter(membership -> clinics.containsKey(membership.clinicId()))
                .map(membership -> {
                    ClinicSummary clinic = clinics.get(membership.clinicId());
                    return new MyClinic(clinic.id(), clinic.name(), membership.role(), clinic.suspended());
                })
                .sorted(BY_NAME)
                .toList();
    }
}
