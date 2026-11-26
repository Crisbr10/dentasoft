package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.UserAccounts.ActiveMembership;
import es.crisbr.dentasoft.identity.internal.UserAccounts.PendingInvitation;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdminException;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUnavailableException;
import es.crisbr.dentasoft.shared.CurrentUser;
import es.crisbr.dentasoft.shared.Logging;
import es.crisbr.dentasoft.tenancy.ClinicDirectory;
import es.crisbr.dentasoft.tenancy.ClinicDirectory.ClinicSummary;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(MyClinics.class);

    private static final Comparator<MyClinic> BY_NAME =
            Comparator.comparing(MyClinic::name).thenComparing(MyClinic::clinicId);

    private final UserAccounts accounts;
    private final ClinicDirectory directory;
    private final InvitationAcceptance acceptance;
    private final KeycloakAdmin keycloak;

    MyClinics(
            UserAccounts accounts, ClinicDirectory directory, InvitationAcceptance acceptance, KeycloakAdmin keycloak) {
        this.accounts = accounts;
        this.directory = directory;
        this.acceptance = acceptance;
        this.keycloak = keycloak;
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
     * transaction, because the change runs with the invitation's clinic active. Then they join the clinic's Keycloak
     * organization, the mirror of their accepted memberships (ADR 0015); if Keycloak fails there, the acceptance
     * stands, since the database decides.
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
        UUID user = CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user"));
        try {
            keycloak.addOrganizationMember(keycloak.ensureOrganization(clinic), user);
        } catch (KeycloakUnavailableException | KeycloakAdminException failure) {
            log.warn(
                    "Invitation {} accepted, but its user was not added to the Keycloak organization",
                    invitationId,
                    Logging.redacted(failure));
        }
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
