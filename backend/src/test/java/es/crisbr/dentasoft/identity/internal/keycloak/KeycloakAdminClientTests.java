package es.crisbr.dentasoft.identity.internal.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.shared.DentasoftUrls;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The Admin API calls of {@link KeycloakAdmin}, against {@link FakeKeycloak}. */
class KeycloakAdminClientTests {

    /** Personal data that must never reach an exception message. */
    private static final String EMAIL = "probe.person+tag@clinic.test";

    private static final String FIRST_NAME = "Probefirstname";
    private static final String LAST_NAME = "Probelastname";
    private static final String APP = "https://app.dentasoft.test";
    private static final Duration LIFESPAN = Duration.ofHours(72);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static FakeKeycloak keycloak;

    private KeycloakAdmin admin;

    @BeforeAll
    static void startKeycloak() {
        keycloak = FakeKeycloak.start();
    }

    @AfterAll
    static void stopKeycloak() {
        keycloak.close();
    }

    @BeforeEach
    void setUp() {
        keycloak.reset();
        admin = client(keycloak.baseUrl(), FakeKeycloak.CLIENT_SECRET, APP, Duration.ofSeconds(5));
    }

    // --- Users ---

    @Test
    void findsAUserByExactEmail() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());
        keycloak.addUser("other@clinic.test", "Other", "Person", true, List.of());

        assertThat(admin.findUserByEmail(EMAIL))
                .contains(new KeycloakUser(id, EMAIL, FIRST_NAME, LAST_NAME, true, true, List.of()));
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/users");
        // The "+" arrives as a "+", not as a space: the query value is encoded.
        assertThat(request.query()).containsEntry("email", EMAIL).containsEntry("exact", "true");
    }

    @Test
    void findsNoUserByAnUnknownOrInvisibleEmail() {
        keycloak.addInvisibleUser(EMAIL);

        assertThat(admin.findUserByEmail(EMAIL)).isEmpty();
        assertThat(admin.findUserByEmail("nobody@clinic.test")).isEmpty();
    }

    @Test
    void findsAUserById() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, false, KeycloakAdmin.INVITATION_ACTIONS);

        assertThat(admin.findUser(id))
                .contains(new KeycloakUser(
                        id, EMAIL, FIRST_NAME, LAST_NAME, true, false, KeycloakAdmin.INVITATION_ACTIONS));
        assertThat(keycloak.requests().getLast().path()).isEqualTo("/users/" + id);
    }

    /** 404: no such user; 403: a user outside the service account's group, such as a platform administrator. */
    @Test
    void findsNoUserThatIsUnknownOrInvisible() {
        UUID invisible = keycloak.addInvisibleUser(EMAIL);

        assertThat(admin.findUser(UUID.randomUUID())).isEmpty();
        assertThat(admin.findUser(invisible)).isEmpty();
    }

    @Test
    void createsAnInvitedUserInTheClinicUsersGroup() {
        UUID id = admin.createUser(EMAIL, FIRST_NAME, LAST_NAME);

        assertThat(keycloak.user(id))
                .contains(new FakeKeycloak.User(
                        id,
                        EMAIL,
                        FIRST_NAME,
                        LAST_NAME,
                        true,
                        false,
                        List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"),
                        List.of("/clinic-users")));
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/users");
        assertThat(JSON.readValue(request.body(), Map.class))
                .isEqualTo(Map.of(
                        "username",
                        EMAIL,
                        "email",
                        EMAIL,
                        "firstName",
                        FIRST_NAME,
                        "lastName",
                        LAST_NAME,
                        "enabled",
                        true,
                        "emailVerified",
                        false,
                        "groups",
                        List.of("/clinic-users"),
                        "requiredActions",
                        List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP")));
    }

    @Test
    void creatingAnExistingEmailIsAConflict() {
        keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());

        Throwable thrown = catchThrowable(() -> admin.createUser(EMAIL, FIRST_NAME, LAST_NAME));

        assertThat(thrown).isInstanceOf(KeycloakConflictException.class);
        assertNoPersonalData(thrown);
    }

    /** A platform administrator's email: the service account cannot see the user, but cannot create it either. */
    @Test
    void creatingTheEmailOfAnInvisibleUserIsAConflict() {
        keycloak.addInvisibleUser(EMAIL);

        assertThatThrownBy(() -> admin.createUser(EMAIL, FIRST_NAME, LAST_NAME))
                .isInstanceOf(KeycloakConflictException.class);
        assertThat(keycloak.users()).hasSize(1);
    }

    @Test
    void sendsTheInvitationEmailWithTheLifespanAndTheWayBackToTheFrontend() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, false, KeycloakAdmin.INVITATION_ACTIONS);

        admin.sendActionsEmail(id);

        assertThat(keycloak.actionsEmails())
                .containsExactly(new FakeKeycloak.ActionsEmail(
                        id,
                        List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"),
                        LIFESPAN.toSeconds(),
                        "dentasoft-web",
                        APP + "/"));
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.path()).isEqualTo("/users/" + id + "/execute-actions-email");
    }

    /** The realm registers exactly {@code ${DENTASOFT_URLS_APP}/} as dentasoft-web's redirect URI. */
    @Test
    void theRedirectUriHasOneTrailingSlashWhateverTheAppUrl() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, false, KeycloakAdmin.INVITATION_ACTIONS);

        client(keycloak.baseUrl() + "/", FakeKeycloak.CLIENT_SECRET, APP + "/", Duration.ofSeconds(5))
                .sendActionsEmail(id);

        assertThat(keycloak.actionsEmails().getLast().redirectUri()).isEqualTo(APP + "/");
    }

    @Test
    void logsTheUserOut() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());

        admin.logout(id);

        assertThat(keycloak.logouts()).containsExactly(id);
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/users/" + id + "/logout");
    }

    // --- Organizations ---

    @Test
    void createsTheOrganizationOfAClinicOnce() {
        UUID clinicId = UUID.randomUUID();

        String id = admin.ensureOrganization(clinicId);
        String again = admin.ensureOrganization(clinicId);

        assertThat(again).isEqualTo(id);
        assertThat(keycloak.organizations())
                .containsExactly(new FakeKeycloak.Organization(id, clinicId.toString(), clinicId.toString(), Set.of()));
        List<FakeKeycloak.Request> requests = keycloak.requests();
        assertThat(requests).extracting(FakeKeycloak.Request::method).containsExactly("GET", "POST", "GET");
        assertThat(requests.getFirst().path()).isEqualTo("/organizations");
        assertThat(requests.getFirst().query())
                .containsEntry("search", clinicId.toString())
                .containsEntry("exact", "true");
        assertThat(JSON.readValue(requests.get(1).body(), Map.class))
                .isEqualTo(Map.of("name", clinicId.toString(), "alias", clinicId.toString()));
    }

    /** Another instance creates the organization between the lookup and the creation: its 409 is not an error. */
    @Test
    void usesTheOrganizationAnotherInstanceCreatedFirst() {
        UUID clinicId = UUID.randomUUID();
        String[] created = new String[1];
        keycloak.beforeNext("POST", "/organizations", () -> created[0] = keycloak.addOrganization(clinicId.toString()));

        assertThat(admin.ensureOrganization(clinicId)).isEqualTo(created[0]);
        assertThat(keycloak.organizations()).hasSize(1);
    }

    @Test
    void addsAMemberOnce() {
        UUID userId = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());
        String organizationId = keycloak.addOrganization(UUID.randomUUID().toString());

        admin.addOrganizationMember(organizationId, userId);
        // Already a member: Keycloak answers 409, which is what was asked for.
        admin.addOrganizationMember(organizationId, userId);

        assertThat(keycloak.organizations().getFirst().members()).containsExactly(userId);
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/organizations/" + organizationId + "/members");
        // The body is the user id as a JSON string.
        assertThat(request.body()).isEqualTo("\"" + userId + "\"");
    }

    @Test
    void removesAMemberAndIgnoresANonMember() {
        UUID userId = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());
        String organizationId = keycloak.addOrganization(UUID.randomUUID().toString());
        keycloak.addMember(organizationId, userId);

        admin.removeOrganizationMember(organizationId, userId);
        // No longer a member: Keycloak answers 404, which is what was asked for.
        admin.removeOrganizationMember(organizationId, userId);

        assertThat(keycloak.organizations().getFirst().members()).isEmpty();
        FakeKeycloak.Request request = keycloak.requests().getLast();
        assertThat(request.method()).isEqualTo("DELETE");
        assertThat(request.path()).isEqualTo("/organizations/" + organizationId + "/members/" + userId);
    }

    @Test
    void tellsWhetherTheUserBelongsToAnyOrganization() {
        UUID userId = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());

        assertThat(admin.belongsToAnyOrganization(userId)).isFalse();
        assertThat(keycloak.requests().getLast().path())
                .isEqualTo("/organizations/members/" + userId + "/organizations");

        keycloak.addMember(keycloak.addOrganization(UUID.randomUUID().toString()), userId);

        assertThat(admin.belongsToAnyOrganization(userId)).isTrue();
    }

    // --- Token ---

    @Test
    void asksForOneTokenAndReusesItUntilItExpires() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());

        admin.findUser(id);
        admin.findUserByEmail(EMAIL);
        admin.logout(id);

        assertThat(keycloak.tokenRequests()).isEqualTo(1);
    }

    /** A token Keycloak no longer accepts fails that call only: the next one asks for a new token. */
    @Test
    void asksForANewTokenAfterKeycloakRejectsTheCachedOne() {
        UUID id = keycloak.addUser(EMAIL, FIRST_NAME, LAST_NAME, true, List.of());
        admin.findUser(id);
        keycloak.revokeTokens();

        assertThatThrownBy(() -> admin.findUser(id))
                .isExactlyInstanceOf(KeycloakAdminException.class)
                .hasMessageContaining("401");
        assertThat(admin.findUser(id)).isPresent();
        assertThat(keycloak.tokenRequests()).isEqualTo(2);
    }

    @Test
    void wrongClientCredentialsAreNotAnOutage() {
        KeycloakAdmin wrongSecret = client(keycloak.baseUrl(), "wrong-secret", APP, Duration.ofSeconds(5));

        Throwable thrown = catchThrowable(() -> wrongSecret.findUserByEmail(EMAIL));

        assertThat(thrown).isExactlyInstanceOf(KeycloakAdminException.class);
        assertNoPersonalData(thrown);
    }

    @Test
    void aFailingTokenEndpointIsAnOutage() {
        keycloak.failNext("POST", FakeKeycloak.TOKEN_PATH, 503);

        Throwable thrown = catchThrowable(() -> admin.findUserByEmail(EMAIL));

        assertThat(thrown).isInstanceOf(KeycloakUnavailableException.class);
        assertNoPersonalData(thrown);
    }

    // --- Failures ---

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverErrorsAreAnOutage(int status) {
        keycloak.failNext("GET", "/users", status);

        Throwable thrown = catchThrowable(() -> admin.findUserByEmail(EMAIL));

        assertThat(thrown).isInstanceOf(KeycloakUnavailableException.class).hasMessageContaining("" + status);
        assertNoPersonalData(thrown);
    }

    @Test
    void aServerErrorOnCreationIsAnOutage() {
        keycloak.failNext("POST", "/users", 500);

        Throwable thrown = catchThrowable(() -> admin.createUser(EMAIL, FIRST_NAME, LAST_NAME));

        assertThat(thrown).isInstanceOf(KeycloakUnavailableException.class);
        assertNoPersonalData(thrown);
    }

    @Test
    void anUnexpectedClientErrorIsNeitherAConflictNorAnOutage() {
        keycloak.failNext("POST", "/users", 400);

        Throwable thrown = catchThrowable(() -> admin.createUser(EMAIL, FIRST_NAME, LAST_NAME));

        assertThat(thrown).isExactlyInstanceOf(KeycloakAdminException.class).hasMessageContaining("400");
        assertNoPersonalData(thrown);
    }

    @Test
    void aSlowKeycloakIsAnOutage() {
        KeycloakAdmin impatient = client(keycloak.baseUrl(), FakeKeycloak.CLIENT_SECRET, APP, Duration.ofMillis(300));
        impatient.findUserByEmail("warm-up@clinic.test");
        keycloak.delayNext("GET", "/users", Duration.ofSeconds(2));

        Throwable thrown = catchThrowable(() -> impatient.findUserByEmail(EMAIL));

        assertThat(thrown).isInstanceOf(KeycloakUnavailableException.class);
        assertNoPersonalData(thrown);
    }

    @Test
    void anUnreachableKeycloakIsAnOutage() {
        String baseUrl;
        try (FakeKeycloak stopped = FakeKeycloak.start()) {
            baseUrl = stopped.baseUrl();
        }
        KeycloakAdmin unreachable = client(baseUrl, FakeKeycloak.CLIENT_SECRET, APP, Duration.ofSeconds(5));

        Throwable thrown = catchThrowable(() -> unreachable.findUserByEmail(EMAIL));

        assertThat(thrown).isInstanceOf(KeycloakUnavailableException.class);
        assertNoPersonalData(thrown);
    }

    // --- KeycloakUser ---

    @Test
    void aUserPrintsNeitherItsEmailNorItsName() {
        KeycloakUser user = new KeycloakUser(UUID.randomUUID(), EMAIL, FIRST_NAME, LAST_NAME, true, true, List.of());

        assertThat(user.toString()).contains(user.id().toString()).doesNotContain(EMAIL, FIRST_NAME, LAST_NAME);
    }

    @Test
    void anInvitationIsPendingUntilTheEmailIsVerifiedAndNoActionIsLeft() {
        UUID id = UUID.randomUUID();

        assertThat(new KeycloakUser(id, EMAIL, FIRST_NAME, LAST_NAME, true, true, List.of()).invitationPending())
                .isFalse();
        assertThat(new KeycloakUser(id, EMAIL, FIRST_NAME, LAST_NAME, true, false, List.of()).invitationPending())
                .isTrue();
        assertThat(new KeycloakUser(id, EMAIL, FIRST_NAME, LAST_NAME, true, true, List.of("CONFIGURE_TOTP"))
                        .invitationPending())
                .isTrue();
    }

    private static KeycloakAdmin client(String baseUrl, String secret, String app, Duration readTimeout) {
        KeycloakAdminProperties properties = new KeycloakAdminProperties(
                baseUrl,
                FakeKeycloak.REALM,
                FakeKeycloak.CLIENT_ID,
                secret,
                Duration.ofSeconds(2),
                readTimeout,
                LIFESPAN);
        return new RestKeycloakAdmin(
                properties, new DentasoftUrls(app, "https://api.dentasoft.test", "https://auth.dentasoft.test"));
    }

    /** Neither the exception nor any of its causes carries the email, the names or a response body. */
    private static void assertNoPersonalData(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            assertThat(String.valueOf(current.getMessage()))
                    .doesNotContain(EMAIL, "probe.person", FIRST_NAME, LAST_NAME, "injected failure", "errorMessage");
            assertThat(current.toString()).doesNotContain(EMAIL, "probe.person");
        }
    }
}
