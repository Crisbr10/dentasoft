package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.identity.internal.MyClinics.MyClinic;
import es.crisbr.dentasoft.identity.internal.MyClinics.MyInvitation;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Accepting an invitation (M02-T05): the invited person sees it in their list of invitations, the membership grants
 * nothing until they accept it, and only they can accept it, once.
 */
@IntegrationTest
class InvitationAcceptanceTests extends ClinicUsersTestBase {

    private static final String EMAIL = "invitada@clinica-a.test";

    @Test
    void anInvitationIsPendingUntilThePersonAcceptsIt() throws SQLException {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        UUID invited = keycloak.userByEmail(EMAIL).orElseThrow().id();
        UUID membership = membershipOf(invited);

        assertThat(asUser(invited, myClinics::ofCurrentUser)).isEmpty();
        assertThat(asUser(invited, myClinics::invitationsOfCurrentUser))
                .extracting(MyInvitation::id, MyInvitation::clinicId, MyInvitation::role)
                .containsExactly(tuple(membership, CLINIC_A, ClinicRole.ODONTOLOGO));

        acceptAs(invited, membership);

        assertThat(asUser(invited, myClinics::ofCurrentUser))
                .extracting(MyClinic::clinicId, MyClinic::role)
                .containsExactly(tuple(CLINIC_A, ClinicRole.ODONTOLOGO));
        assertThat(asUser(invited, myClinics::invitationsOfCurrentUser)).isEmpty();
        assertThat(detailsOf(invited).invitationPending()).isFalse();
        List<StoredEntry> audit = AuditLogDatabase.entries(CLINIC_A, membership);
        assertThat(audit).extracting(StoredEntry::action).containsExactly("CREATE", "UPDATE");
        assertThat(audit).extracting(StoredEntry::actorId).containsExactly(ADMIN, invited);
    }

    @Test
    void onlyThePersonInvitedAcceptsAnInvitationAndOnlyOnce() {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        UUID invited = keycloak.userByEmail(EMAIL).orElseThrow().id();
        UUID membership = membershipOf(invited);

        assertThatThrownBy(() -> acceptAs(ADMIN, membership)).isInstanceOf(MembershipNotFoundException.class);
        assertThat(detailsOf(invited).invitationPending()).isTrue();

        acceptAs(invited, membership);

        assertThatThrownBy(() -> acceptAs(invited, membership)).isInstanceOf(MembershipNotFoundException.class);
    }

    @Test
    void aCancelledInvitationCannotBeAccepted() {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        UUID invited = keycloak.userByEmail(EMAIL).orElseThrow().id();
        ClinicUserDetails details = detailsOf(invited);
        asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThat(asUser(invited, myClinics::invitationsOfCurrentUser)).isEmpty();
        assertThatThrownBy(() -> acceptAs(invited, details.id())).isInstanceOf(MembershipNotFoundException.class);
    }

    /** Inviting a former member again needs their acceptance again: being invited once is not a standing yes. */
    @Test
    void invitingAFormerMemberAgainNeedsTheirAcceptanceAgain() throws SQLException {
        UUID former = member(CLINIC_A, ClinicRole.RECEPCION);
        String email = keycloak.user(former).orElseThrow().email();
        ClinicUserDetails details = detailsOf(former);
        asAdmin(() -> users.deactivate(details.id(), details.version()));

        asAdmin(() -> users.invite(invitation(email, ClinicRole.ODONTOLOGO)));

        assertThat(detailsOf(former).invitationPending()).isTrue();
        assertThat(asUser(former, myClinics::ofCurrentUser)).isEmpty();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, former)).contains("ODONTOLOGO/true");
    }

    private static <T> T asUser(UUID user, Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        TestSecurityContext.runAs(user, List.of(), () -> result.set(operation.get()));
        return result.get();
    }
}
