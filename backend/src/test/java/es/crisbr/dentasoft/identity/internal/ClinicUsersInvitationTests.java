package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.FakeKeycloak.ActionsEmail;
import es.crisbr.dentasoft.FakeKeycloak.User;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.WithTestEnvironment;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;

/**
 * Inviting people to a clinic (M02-T05) against the fake Keycloak: the Keycloak user, the organization, the account
 * and membership in the database and the invitation email, in that order, with what happens when a step fails.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ClinicUsersInvitationTests extends ClinicUsersTestBase {

    private static final String EMAIL = "ana.nueva@clinica-a.test";

    private static final long SECONDS_IN_72_HOURS = 72 * 3600;

    @Test
    void invitingSomeoneNewCreatesTheirUserMembershipAndSendsTheEmailWithTheRequiredActions() throws SQLException {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));

        User created = keycloak.userByEmail(EMAIL).orElseThrow();
        assertThat(created.groups()).containsExactly(FakeKeycloak.CLINIC_USERS_GROUP);
        assertThat(created.emailVerified()).isFalse();
        assertThat(created.requiredActions()).containsExactlyElementsOf(KeycloakAdmin.INVITATION_ACTIONS);
        assertThat(created.firstName()).isEqualTo("Ana");
        // Not in the clinic's organization until they accept: a pending invitation is not one of their clinics.
        assertThat(keycloak.organizations()).isEmpty();
        assertThat(IdentityTestDatabase.userStatus(created.id())).contains("ACTIVE");
        assertThat(IdentityTestDatabase.userCreatedBy(created.id())).contains(ADMIN);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, created.id())).contains("ODONTOLOGO/true");
        assertThat(keycloak.actionsEmails())
                .containsExactly(new ActionsEmail(
                        created.id(),
                        KeycloakAdmin.INVITATION_ACTIONS,
                        SECONDS_IN_72_HOURS,
                        "dentasoft-web",
                        WithTestEnvironment.APP + "/"));
        List<StoredEntry> audit = AuditLogDatabase.entries(CLINIC_A, membershipOf(created.id()));
        assertThat(audit).extracting(StoredEntry::action).containsExactly("CREATE");
        assertThat(audit).extracting(StoredEntry::actorId).containsExactly(ADMIN);
    }

    /**
     * The usual case of someone who already works in another clinic: the same Keycloak user, their account left as it
     * is (it is not visible to the application, V008), and no email, since their account is set up.
     */
    @Test
    void invitingSomeoneFromAnotherClinicReusesTheirUserAndSendsNoEmail() throws SQLException {
        UUID existing = keycloak.addUser(EMAIL, "Ana", "Pruebas Inventada", true, List.of());
        IdentityTestDatabase.insertUser(existing);
        IdentityTestDatabase.insertMembership(CLINIC_B, existing, ClinicRole.RECEPCION, true);

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.HIGIENISTA)));

        assertThat(keycloak.users()).extracting(User::id).containsExactly(existing);
        assertThat(keycloak.actionsEmails()).isEmpty();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, existing)).contains("HIGIENISTA/true");
        assertThat(IdentityTestDatabase.membership(CLINIC_B, existing)).contains("RECEPCION/true");
        assertThat(IdentityTestDatabase.userCreatedBy(existing)).isEmpty();
    }

    @Test
    void invitingSomeoneWhoHasNotSetUpTheirAccountEmailsThemAgain() {
        UUID pending = keycloak.addUser(EMAIL, "Ana", "Pruebas Inventada", false, KeycloakAdmin.INVITATION_ACTIONS);

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.RECEPCION)));

        assertThat(keycloak.users()).hasSize(1);
        assertThat(keycloak.actionsEmails()).extracting(ActionsEmail::userId).containsExactly(pending);
    }

    /** The email of a user outside clinic-users, such as a platform administrator: nothing happens, and no error. */
    @Test
    void invitingTheEmailOfAPlatformAdministratorDoesNothing() throws SQLException {
        UUID platformAdmin = keycloak.addInvisibleUser(EMAIL);

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ADMIN_CLINICA)));

        assertThat(keycloak.users()).extracting(User::id).containsExactly(platformAdmin);
        assertThat(keycloak.actionsEmails()).isEmpty();
        assertThat(keycloak.organizations()).isEmpty();
        assertThat(IdentityTestDatabase.userStatus(platformAdmin)).isEmpty();
    }

    @Test
    void anActiveMemberCannotBeInvitedAgain() throws SQLException {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));

        assertThatThrownBy(() -> asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.RECEPCION))))
                .isInstanceOf(AlreadyMemberException.class);
        UUID user = keycloak.userByEmail(EMAIL).orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, user)).contains("ODONTOLOGO/true");
    }

    @Test
    void invitingAFormerMemberReactivatesTheirMembershipWithTheNewRole() throws SQLException {
        UUID former = keycloak.addUser(EMAIL, "Ana", "Pruebas Inventada", true, List.of());
        IdentityTestDatabase.insertUser(former);
        IdentityTestDatabase.insertMembership(CLINIC_A, former, ClinicRole.RECEPCION, false);

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.FACTURACION)));

        assertThat(IdentityTestDatabase.membership(CLINIC_A, former)).contains("FACTURACION/true");
        assertThat(AuditLogDatabase.actions(CLINIC_A, membershipOf(former))).containsExactly("UPDATE");
    }

    /** Keycloak fails before the transaction: nothing is written, and repeating the invitation does all of it once. */
    @Test
    void whenKeycloakFailsTheInvitationCanBeRepeatedWithoutDuplicates() throws SQLException {
        keycloak.failNext("POST", "/users", 500);

        assertThatThrownBy(() -> asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO))))
                .isInstanceOf(IdentityProviderUnavailableException.class);
        assertThat(keycloak.users()).isEmpty();

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));

        assertThat(keycloak.users()).hasSize(1);
        UUID user = keycloak.userByEmail(EMAIL).orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, user)).contains("ODONTOLOGO/true");
        assertThat(keycloak.actionsEmails()).extracting(ActionsEmail::userId).containsExactly(user);
    }

    /** The email fails after the commit: the membership stays, and the email can be sent again. */
    @Test
    void aFailedInvitationEmailLeavesTheMembershipAndCanBeSentAgain() throws SQLException {
        keycloak.failNext("PUT", "/users", 500);

        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));

        UUID user = keycloak.userByEmail(EMAIL).orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, user)).contains("ODONTOLOGO/true");
        assertThat(keycloak.actionsEmails()).isEmpty();
        assertThat(detailsOf(user).invitationPending()).isTrue();

        UUID membership = membershipOf(user);
        asAdmin(() -> users.resendInvitation(membership));

        assertThat(keycloak.actionsEmails()).extracting(ActionsEmail::userId).containsExactly(user);
    }

    /**
     * Someone who already had an account (from another clinic) gets no email when the invitation is sent again, but
     * the answer is the same as for someone new: it reveals nothing about other clinics.
     */
    @Test
    void resendingToSomeoneWithAnAccountSucceedsWithoutAnEmail() {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        UUID user = keycloak.userByEmail(EMAIL).orElseThrow().id();
        keycloak.completeInvitation(user);
        UUID membership = membershipOf(user);

        asAdmin(() -> users.resendInvitation(membership));

        assertThat(keycloak.actionsEmails()).hasSize(1);
    }

    @Test
    void anAcceptedInvitationIsNotSentAgain() {
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        UUID user = keycloak.userByEmail(EMAIL).orElseThrow().id();
        UUID membership = membershipOf(user);
        acceptAs(user, membership);

        assertThatThrownBy(() -> asAdmin(() -> users.resendInvitation(membership)))
                .isInstanceOf(InvitationNotPendingException.class);
        assertThat(keycloak.actionsEmails()).hasSize(1);
    }

    /**
     * Until the person accepts, the administrator sees the same for someone new and for someone who already works in
     * another clinic: the email they typed and a pending invitation, never the name stored in Keycloak or whether the
     * account was already set up (ADR 0016).
     */
    @Test
    void untilAcceptedTheListLooksTheSameForSomeoneNewAndSomeoneFromAnotherClinic() throws SQLException {
        String known = "ya-trabaja@clinica-b.test";
        UUID existing = keycloak.addUser(known, "Nombre", "Guardado En Keycloak", true, List.of());
        IdentityTestDatabase.insertUser(existing);
        IdentityTestDatabase.insertMembership(CLINIC_B, existing, ClinicRole.RECEPCION, true);

        asAdmin(() -> users.invite(new ClinicUsers.Invitation(EMAIL, "Ana", "Pruebas", ClinicRole.ODONTOLOGO)));
        asAdmin(() -> users.invite(new ClinicUsers.Invitation(known, "Otro", "Nombre", ClinicRole.ODONTOLOGO)));

        UUID created = keycloak.userByEmail(EMAIL).orElseThrow().id();
        ClinicUserDetails newPerson = detailsOf(created);
        ClinicUserDetails knownPerson = detailsOf(existing);
        assertThat(List.of(newPerson, knownPerson)).allSatisfy(details -> {
            assertThat(details.invitationPending()).isTrue();
            assertThat(details.active()).isTrue();
            assertThat(details.role()).isEqualTo(ClinicRole.ODONTOLOGO);
            assertThat(details.profile().firstName()).isNull();
            assertThat(details.profile().lastName()).isNull();
        });
        assertThat(newPerson.profile().email()).isEqualTo(EMAIL);
        assertThat(knownPerson.profile().email()).isEqualTo(known);
        asAdmin(() -> users.resendInvitation(newPerson.id()));
        asAdmin(() -> users.resendInvitation(knownPerson.id()));

        acceptAs(existing, knownPerson.id());

        assertThat(detailsOf(existing).profile())
                .isEqualTo(new ClinicUserDetails.Profile(known, "Nombre", "Guardado En Keycloak"));
    }

    /**
     * Invitations and resends count together, per clinic and day. In clinic B, which no other test invites to: while
     * Redis is slow the quota falls back to a bucket of this instance, which tests cannot empty.
     */
    @Test
    void aClinicSendsAtMostTwentyInvitationsADay() throws SQLException {
        IdentityTestDatabase.insertMembership(CLINIC_B, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        for (int i = 1; i <= ClinicUsers.INVITATIONS_PER_DAY - 1; i++) {
            String email = "persona" + i + "@clinica-b.test";
            inClinicB(() -> users.invite(invitation(email, ClinicRole.RECEPCION)));
        }
        UUID first =
                keycloak.userByEmail("persona1@clinica-b.test").orElseThrow().id();
        UUID membership = inClinicB(() -> users.list(PageRequest.of(0, 50)).stream()
                .filter(details -> details.userId().equals(first))
                .findFirst()
                .orElseThrow()
                .id());
        inClinicB(() -> users.resendInvitation(membership));

        assertThatThrownBy(() -> inClinicB(() -> users.invite(invitation(EMAIL, ClinicRole.RECEPCION))))
                .isInstanceOfSatisfying(
                        InvitationLimitExceededException.class,
                        refused -> assertThat(
                                        Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                                .isPositive());
        assertThatThrownBy(() -> inClinicB(() -> users.resendInvitation(membership)))
                .isInstanceOf(InvitationLimitExceededException.class);
        assertThat(keycloak.userByEmail(EMAIL)).isEmpty();
        assertThat(keycloak.actionsEmails()).hasSize(ClinicUsers.INVITATIONS_PER_DAY);
    }

    private static <T> T inClinicB(java.util.function.Supplier<T> operation) {
        return as(ADMIN, List.of("ADMIN_CLINICA"), CLINIC_B, operation);
    }

    private static void inClinicB(Runnable operation) {
        inClinicB(() -> {
            operation.run();
            return null;
        });
    }

    /** The name and email typed by the administrator never reach the log, not even when Keycloak fails. */
    @Test
    void theNameAndEmailOfTheInvitedPersonAreNeverLogged(CapturedOutput output) {
        keycloak.failNext("POST", "/users", 500);
        assertThatThrownBy(() -> asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO))))
                .isInstanceOf(IdentityProviderUnavailableException.class);
        keycloak.failNext("PUT", "/users", 500);
        asAdmin(() -> users.invite(invitation(EMAIL, ClinicRole.ODONTOLOGO)));
        keycloak.addInvisibleUser("plataforma.oculta@clinica-a.test");
        asAdmin(() -> users.invite(invitation("plataforma.oculta@clinica-a.test", ClinicRole.ODONTOLOGO)));

        assertThat(output.getAll())
                .contains("not sent")
                .doesNotContain(EMAIL)
                .doesNotContain("plataforma.oculta")
                .doesNotContain("Pruebas Inventada");
    }
}
