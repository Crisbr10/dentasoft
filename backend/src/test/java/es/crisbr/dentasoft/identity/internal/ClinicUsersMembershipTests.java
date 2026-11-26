package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakAdmin;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * Changing roles, deactivating memberships and listing the clinic's users (M02-T05): only {@code ADMIN_CLINICA}, never
 * on their own membership, and never leaving the clinic without an administrator.
 */
@IntegrationTest
class ClinicUsersMembershipTests extends ClinicUsersTestBase {

    // --- Who may manage users ---

    static Stream<String> operations() {
        return Stream.of("list", "invite", "resend", "changeRole", "deactivate");
    }

    private Runnable operation(String name, UUID membership) {
        return switch (name) {
            case "list" -> () -> users.list(PageRequest.of(0, 20));
            case "invite" -> () -> users.invite(invitation("alguien@clinica-a.test", ClinicRole.RECEPCION));
            case "resend" -> () -> users.resendInvitation(membership);
            case "changeRole" -> () -> users.changeRole(membership, ClinicRole.ODONTOLOGO, 0);
            case "deactivate" -> () -> users.deactivate(membership, 0);
            default -> throw new IllegalArgumentException(name);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"ODONTOLOGO", "HIGIENISTA", "RECEPCION", "FACTURACION", "PLATFORM_ADMIN"})
    void onlyClinicAdministratorsManageUsers(String role) throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        UUID membership = membershipOf(member);
        keycloak.reset();

        for (String name : operations().toList()) {
            assertThatThrownBy(() -> as(ADMIN, List.of(role), () -> {
                        operation(name, membership).run();
                        return null;
                    }))
                    .as(name)
                    .isInstanceOf(AccessDeniedException.class);
        }

        assertThat(keycloak.requests()).isEmpty();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, member)).contains("RECEPCION/true");
    }

    @ParameterizedTest
    @MethodSource("operations")
    void withoutAUserNothingIsManaged(String name) throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        UUID membership = membershipOf(member);

        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(name, membership)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    // --- Roles ---

    @Test
    void anAdministratorChangesTheRoleOfAnotherUserAndItIsAudited() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        ClinicUserDetails before = detailsOf(member);

        ClinicUserDetails after = asAdmin(() -> users.changeRole(before.id(), ClinicRole.ODONTOLOGO, before.version()));

        assertThat(after.role()).isEqualTo(ClinicRole.ODONTOLOGO);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, member)).contains("ODONTOLOGO/true");
        assertThat(AuditLogDatabase.actions(CLINIC_A, before.id())).containsExactly("UPDATE");
    }

    @Test
    void noOneChangesTheirOwnRoleOrDeactivatesThemselves() throws SQLException {
        member(CLINIC_A, ClinicRole.ADMIN_CLINICA);
        ClinicUserDetails own = detailsOf(ADMIN);

        assertThatThrownBy(() -> asAdmin(() -> users.changeRole(own.id(), ClinicRole.RECEPCION, own.version())))
                .isInstanceOf(OwnMembershipException.class);
        assertThatThrownBy(() -> asAdmin(() -> users.deactivate(own.id(), own.version())))
                .isInstanceOf(OwnMembershipException.class);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, ADMIN)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void aChangeMadeOnAnOldVersionIsAConflict() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        ClinicUserDetails details = detailsOf(member);
        asAdmin(() -> users.changeRole(details.id(), ClinicRole.HIGIENISTA, details.version()));

        assertThatThrownBy(
                        () -> asAdmin(() -> users.changeRole(details.id(), ClinicRole.ODONTOLOGO, details.version())))
                .isInstanceOf(MembershipVersionConflictException.class);
        assertThatThrownBy(() -> asAdmin(() -> users.deactivate(details.id(), details.version())))
                .isInstanceOf(MembershipVersionConflictException.class);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, member)).contains("HIGIENISTA/true");
    }

    @Test
    void anotherClinicsMembershipIsNotFound() throws SQLException {
        UUID outsider = member(CLINIC_B, ClinicRole.ADMIN_CLINICA);
        UUID userInB = UUID.randomUUID();
        IdentityTestDatabase.insertUser(userInB);
        createdUsers.add(userInB);
        UUID membershipInB = IdentityTestDatabase.insertMembership(CLINIC_B, userInB, ClinicRole.RECEPCION, true);

        assertThatThrownBy(() -> asAdmin(() -> users.changeRole(membershipInB, ClinicRole.ODONTOLOGO, 0)))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThatThrownBy(() -> asAdmin(() -> users.deactivate(membershipInB, 0)))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThatThrownBy(() -> asAdmin(() -> users.resendInvitation(membershipInB)))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThat(IdentityTestDatabase.membership(CLINIC_B, outsider)).contains("ADMIN_CLINICA/true");
    }

    /**
     * Two administrators demote each other at the same time. Their changes lock the clinic's administrators and run
     * one after the other; the second finds its author no longer an administrator, so the clinic keeps one, whichever
     * runs first.
     */
    @Test
    void twoAdministratorsDemotingEachOtherAtOnceLeaveTheClinicWithOne() throws Exception {
        UUID second = member(CLINIC_A, ClinicRole.ADMIN_CLINICA);
        ClinicUserDetails adminMembership = detailsOf(ADMIN);
        ClinicUserDetails secondMembership = detailsOf(second);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> demote(ADMIN, secondMembership, start, outcomes)),
                    executor.submit(() -> demote(second, adminMembership, start, outcomes)));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(ClinicUserDetails.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AccessDeniedException.class::isInstance).hasSize(1);
        long admins = Stream.of(ADMIN, second)
                .filter(user -> {
                    try {
                        return IdentityTestDatabase.membership(CLINIC_A, user)
                                .orElseThrow()
                                .equals("ADMIN_CLINICA/true");
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .count();
        assertThat(admins).isEqualTo(1);
    }

    private void demote(UUID actor, ClinicUserDetails target, CountDownLatch start, List<Object> outcomes) {
        try {
            start.await();
            outcomes.add(as(
                    actor,
                    List.of("ADMIN_CLINICA"),
                    () -> users.changeRole(target.id(), ClinicRole.ODONTOLOGO, target.version())));
        } catch (AccessDeniedException refused) {
            outcomes.add(refused);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Deactivation ---

    @Test
    void deactivatingAUserOfNoOtherClinicRemovesThemFromTheOrganizationAndClosesTheirSessions() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        String organization = keycloak.addOrganization(CLINIC_A.toString());
        keycloak.addMember(organization, member);
        ClinicUserDetails details = detailsOf(member);

        ClinicUserDetails deactivated = asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThat(deactivated.active()).isFalse();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, member)).contains("RECEPCION/false");
        assertThat(keycloak.organizationByAlias(CLINIC_A.toString())
                        .orElseThrow()
                        .members())
                .doesNotContain(member);
        assertThat(keycloak.logouts()).containsExactly(member);
        assertThat(AuditLogDatabase.actions(CLINIC_A, details.id())).containsExactly("UPDATE");
    }

    @Test
    void deactivatingAUserWhoWorksInAnotherClinicKeepsTheirSessions() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        keycloak.addMember(keycloak.addOrganization(CLINIC_A.toString()), member);
        keycloak.addMember(keycloak.addOrganization(CLINIC_B.toString()), member);
        ClinicUserDetails details = detailsOf(member);

        asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThat(keycloak.organizationByAlias(CLINIC_B.toString())
                        .orElseThrow()
                        .members())
                .containsExactly(member);
        assertThat(keycloak.logouts()).isEmpty();
    }

    /** Keycloak fails after the deactivation: it stands, and repeating it completes Keycloak's side. */
    @Test
    void deactivatingAgainCompletesWhatKeycloakMissed() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        keycloak.addMember(keycloak.addOrganization(CLINIC_A.toString()), member);
        ClinicUserDetails details = detailsOf(member);
        keycloak.failNext("DELETE", "/organizations", 500);

        asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThat(IdentityTestDatabase.membership(CLINIC_A, member)).contains("RECEPCION/false");
        assertThat(keycloak.logouts()).isEmpty();

        // The client repeats the request with the version it had: the membership is already inactive.
        asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThat(keycloak.organizationByAlias(CLINIC_A.toString())
                        .orElseThrow()
                        .members())
                .isEmpty();
        assertThat(keycloak.logouts()).containsExactly(member);
        assertThat(AuditLogDatabase.actions(CLINIC_A, details.id())).containsExactly("UPDATE");
    }

    @Test
    void theRoleOfAnInactiveMembershipCannotChange() throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.RECEPCION);
        ClinicUserDetails details = detailsOf(member);
        ClinicUserDetails deactivated = asAdmin(() -> users.deactivate(details.id(), details.version()));

        assertThatThrownBy(() ->
                        asAdmin(() -> users.changeRole(details.id(), ClinicRole.ODONTOLOGO, deactivated.version())))
                .isInstanceOf(MembershipInactiveException.class);
        assertThatThrownBy(() -> asAdmin(() -> users.resendInvitation(details.id())))
                .isInstanceOf(MembershipInactiveException.class);
    }

    // --- The list ---

    @Test
    void theListShowsEachUsersProfileFromKeycloak() throws SQLException {
        UUID pending = keycloak.addUser(
                "pendiente@clinica-a.test", "Luis", "Pruebas", false, KeycloakAdmin.INVITATION_ACTIONS);
        IdentityTestDatabase.insertUser(pending);
        IdentityTestDatabase.insertMembership(CLINIC_A, pending, ClinicRole.ODONTOLOGO, true);

        Page<ClinicUserDetails> page = asAdmin(() -> users.list(PageRequest.of(0, 20)));

        assertThat(page.getTotalElements()).isEqualTo(2);
        ClinicUserDetails listed = page.stream()
                .filter(details -> details.userId().equals(pending))
                .findFirst()
                .orElseThrow();
        assertThat(listed.role()).isEqualTo(ClinicRole.ODONTOLOGO);
        assertThat(listed.profile())
                .isEqualTo(new ClinicUserDetails.Profile("pendiente@clinica-a.test", "Luis", "Pruebas", true));
        // The administrator is not in the fake Keycloak: no profile, but still listed.
        assertThat(page.stream().filter(details -> details.userId().equals(ADMIN)))
                .singleElement()
                .extracting(ClinicUserDetails::profile)
                .isNull();
    }

    @Test
    void theListStillComesWhenKeycloakDoesNotAnswer() throws SQLException {
        UUID first = keycloak.addUser("uno@clinica-a.test", "Uno", "Pruebas", true, List.of());
        IdentityTestDatabase.insertUser(first);
        IdentityTestDatabase.insertMembership(CLINIC_A, first, ClinicRole.ODONTOLOGO, true);
        keycloak.failNext("GET", "/users", 503);

        Page<ClinicUserDetails> page = asAdmin(() -> users.list(PageRequest.of(0, 20)));

        assertThat(page.getContent())
                .hasSize(2)
                .allSatisfy(details -> assertThat(details.profile()).isNull());
        // Once Keycloak failed, the rest of the page did not ask it again.
        assertThat(keycloak.requests())
                .filteredOn(request -> request.method().equals("GET"))
                .hasSize(1);
    }

    /** Each user of a page costs one request to Keycloak, so pages are smaller than the API's usual limit. */
    @Test
    void pagesHaveAtMostFiftyUsers() {
        Page<ClinicUserDetails> page = asAdmin(() -> users.list(PageRequest.of(0, 100)));

        assertThat(page.getSize()).isEqualTo(ClinicMemberships.MAX_PAGE_SIZE);
    }
}
