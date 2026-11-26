package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The versioned Keycloak realm ({@code infra/keycloak/realm-dentasoft.json}, M02-T01) and the local test users.
 *
 * <p>Keycloak imports the realm only when it does not exist yet, so a mistake here reaches every new environment
 * unnoticed: no domain (ADR 0006), no wildcard redirect, no secret, and the security settings the module requires
 * (PKCE, OTP, brute force protection, short tokens, security events).
 */
class KeycloakRealmFileTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path INFRA = Path.of("infra");

    private static final Path REALM_FILE = Path.of("keycloak", "realm-dentasoft.json");

    private static final Path USERS_FILE = Path.of("keycloak", "local", "dentasoft-users-0.json");

    /**
     * An environment placeholder, such as {@code ${DENTASOFT_KC_SMTP_PASSWORD}}; group 1 is the variable name. With
     * {@code matches()} the whole value must be one placeholder; with {@code find()} it finds each one in a value.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(DENTASOFT_[A-Z0-9_]+)}");

    /** Where Keycloak reads the realm: named {@code <realm>-realm.json} so it pairs the users file with it. */
    private static final String REALM_MOUNT =
            "./keycloak/realm-dentasoft.json:/opt/keycloak/data/import/dentasoft-realm.json:ro";

    private static final String USERS_MOUNT =
            "./keycloak/local/dentasoft-users-0.json:/opt/keycloak/data/import/dentasoft-users-0.json:ro";

    /** A URL, or a domain of the project. Placeholders such as {@code ${DENTASOFT_URLS_APP}/} do not match. */
    private static final Pattern FIXED_URL = Pattern.compile("://|crisbr\\.es", Pattern.CASE_INSENSITIVE);

    /** Keys whose value is a credential (SMTP password, client secret): it must be empty or a placeholder. */
    private static final Set<String> SECRET_KEYS = Set.of("password", "secret", "clientSecret");

    /** Keys of exported credentials (hashes and salts): never allowed in a versioned file. */
    private static final List<String> EXPORTED_CREDENTIAL_KEYS =
            List.of("secretData", "credentialData", "hashedSaltedValue");

    /** The confidential client the backend uses for the Keycloak Admin API (M02-T05). */
    private static final String BACKEND_CLIENT = "dentasoft-backend";

    private static final String SERVICE_ACCOUNT = "service-account-dentasoft-backend";

    /** The group of every clinic user. Platform administrators are never in it (FGAP v2, M02-T05). */
    private static final String CLINIC_USERS = "/clinic-users";

    /**
     * The only {@code realm-management} roles of the backend service account. With {@code manage-users} it could
     * assign {@code PLATFORM_ADMIN} or reset a platform administrator's password or OTP; these roles, plus the
     * fine-grained permission on {@link #CLINIC_USERS}, limit it to clinic users.
     */
    private static final Set<String> SERVICE_ACCOUNT_ROLES =
            Set.of("query-users", "query-groups", "manage-organizations");

    /** What the service account may do with the members of {@link #CLINIC_USERS}. */
    private static final Set<String> CLINIC_USERS_SCOPES =
            Set.of("view", "view-members", "manage-members", "manage-membership");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final JsonNode REALM = read(REALM_FILE);

    private static final JsonNode USERS = read(USERS_FILE);

    @Test
    void containsNoFixedUrlOrDomain() {
        assertThat(fixedUrls(REALM)).isEmpty();
        assertThat(fixedUrls(USERS)).isEmpty();
    }

    @Test
    void detectsFixedUrls() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        ((ObjectNode) client(realm, "dentasoft-web").path("attributes"))
                .put("post.logout.redirect.uris", "https://dentasoft.example.org/");
        ((ArrayNode) client(realm, "dentasoft-web").path("webOrigins")).add("http://dentasoft.crisbr.es");

        assertThat(fixedUrls(realm))
                .containsExactlyInAnyOrder(
                        "clients[0].attributes.post.logout.redirect.uris: https://dentasoft.example.org/",
                        "clients[0].webOrigins[1]: http://dentasoft.crisbr.es");
    }

    @Test
    void redirectUrisAndWebOriginsAreExactAndComeFromTheAppUrl() {
        assertThat(wildcards(REALM)).isEmpty();

        JsonNode web = client(REALM, "dentasoft-web");
        assertThat(strings(web.path("redirectUris"))).containsExactly("${DENTASOFT_URLS_APP}/");
        assertThat(strings(web.path("webOrigins"))).containsExactly("${DENTASOFT_URLS_APP}");
        assertThat(web.path("attributes").path("post.logout.redirect.uris").asString())
                .isEqualTo("${DENTASOFT_URLS_APP}/");
    }

    @Test
    void detectsWildcardRedirectUrisAndWebOrigins() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        JsonNode web = client(realm, "dentasoft-web");
        ((ArrayNode) web.path("redirectUris")).add("${DENTASOFT_URLS_APP}/*");
        ((ArrayNode) web.path("webOrigins")).add("*");
        ((ObjectNode) web.path("attributes")).put("post.logout.redirect.uris", "${DENTASOFT_URLS_APP}/##+");

        assertThat(wildcards(realm))
                .containsExactlyInAnyOrder(
                        "dentasoft-web redirectUris: ${DENTASOFT_URLS_APP}/*",
                        "dentasoft-web webOrigins: *",
                        "dentasoft-web post.logout.redirect.uris: +");
    }

    @Test
    void containsNoPasswordOrSecretOutsideEnvironmentPlaceholders() {
        assertThat(secrets(REALM)).isEmpty();
        assertThat(secrets(USERS)).isEmpty();
    }

    @Test
    void detectsPasswordsAndExportedCredentials() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        ((ObjectNode) realm.path("smtpServer")).put("password", "not-a-placeholder");
        ObjectNode users = (ObjectNode) USERS.deepCopy();
        ObjectNode credential =
                (ObjectNode) users.path("users").get(0).path("credentials").get(0);
        credential.put("value", "Plain-Password-1");
        credential.put("secretData", "{\"value\":\"hash\"}");

        assertThat(secrets(realm)).containsExactly("smtpServer.password");
        assertThat(secrets(users))
                .containsExactlyInAnyOrder("users[0].credentials[0].value", "users[0].credentials[0].secretData");
    }

    @Test
    void webClientIsPublicWithPkceAndOnlyTheAuthorizationCodeFlow() {
        JsonNode web = client(REALM, "dentasoft-web");

        assertThat(web.path("publicClient").asBoolean()).isTrue();
        assertThat(web.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(web.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(web.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(web.path("serviceAccountsEnabled").asBoolean(true)).isFalse();
        assertThat(web.path("attributes").path("pkce.code.challenge.method").asString())
                .isEqualTo("S256");
        assertThat(web.path("attributes")
                        .path("oauth2.device.authorization.grant.enabled")
                        .asString())
                .isEqualTo("false");
    }

    @Test
    void apiScopeAddsTheApiAudienceToTheWebClientTokens() {
        JsonNode scope = named(REALM.path("clientScopes"), "name", "dentasoft-api");
        JsonNode audience = named(scope.path("protocolMappers"), "protocolMapper", "oidc-audience-mapper");

        assertThat(audience.path("config").path("included.custom.audience").asString())
                .isEqualTo("dentasoft-api");
        assertThat(audience.path("config").path("access.token.claim").asString())
                .isEqualTo("true");
        assertThat(strings(client(REALM, "dentasoft-web").path("defaultClientScopes")))
                .contains("dentasoft-api", "basic", "roles");
    }

    @Test
    void onlyThePlatformAdminRoleReachesTheWebClientTokens() {
        assertThat(strings(REALM.path("roles").path("realm"), "name")).containsExactly("PLATFORM_ADMIN");
        assertThat(client(REALM, "dentasoft-web").path("fullScopeAllowed").asBoolean(true))
                .isFalse();
        JsonNode mapping = named(REALM.path("scopeMappings"), "client", "dentasoft-web");
        assertThat(strings(mapping.path("roles"))).containsExactly("PLATFORM_ADMIN");
        // Client roles (realm-management...) never reach the web client tokens either.
        assertThat(clientScopeMappingClients(REALM)).doesNotContain("dentasoft-web");
    }

    @Test
    void backendClientIsConfidentialWithOnlyAServiceAccount() {
        assertBackendClientHasOnlyAServiceAccount(REALM);
    }

    @Test
    void detectsInteractiveFlowsAndRedirectsOnTheBackendClient() {
        ObjectNode flow = (ObjectNode) REALM.deepCopy();
        ((ObjectNode) client(flow, BACKEND_CLIENT)).put("directAccessGrantsEnabled", true);
        ObjectNode redirect = (ObjectNode) REALM.deepCopy();
        ((ObjectNode) client(redirect, BACKEND_CLIENT)).putArray("redirectUris").add("${DENTASOFT_URLS_APP}/");

        assertThatThrownBy(() -> assertBackendClientHasOnlyAServiceAccount(flow))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("directAccessGrantsEnabled");
        assertThatThrownBy(() -> assertBackendClientHasOnlyAServiceAccount(redirect))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("redirectUris");
    }

    @Test
    void backendServiceAccountHasOnlyTheMinimalRealmManagementRoles() {
        assertBackendServiceAccountHasOnlyTheMinimalRoles(REALM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manage-users", "view-users", "manage-realm", "realm-admin", "manage-clients"})
    void detectsMoreRealmManagementRolesForTheBackendServiceAccount(String role) {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        JsonNode account = named(realm.path("users"), "username", SERVICE_ACCOUNT);
        ((ArrayNode) account.path("clientRoles").path("realm-management")).add(role);

        assertThatThrownBy(() -> assertBackendServiceAccountHasOnlyTheMinimalRoles(realm))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manage-users", "view-users", "manage-realm", "realm-admin", "manage-clients"})
    void detectsMoreRealmManagementScopeMappingsForTheBackendClient(String role) {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        JsonNode mapping = named(realm.path("clientScopeMappings").path("realm-management"), "client", BACKEND_CLIENT);
        ((ArrayNode) mapping.path("roles")).add(role);

        assertThatThrownBy(() -> assertBackendServiceAccountHasOnlyTheMinimalRoles(realm))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(role);
    }

    @Test
    void backendServiceAccountManagesOnlyTheMembersOfClinicUsers() {
        assertBackendServiceAccountManagesOnlyClinicUsers(REALM);
    }

    @Test
    void detectsAClientPolicyInsteadOfAUserPolicy() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        ObjectNode policy = (ObjectNode) named(adminPolicies(realm), "type", "user");
        policy.put("type", "client");
        ObjectNode config = (ObjectNode) policy.path("config");
        config.remove("users");
        config.put("clients", "[\"" + BACKEND_CLIENT + "\"]");

        assertThatThrownBy(() -> assertBackendServiceAccountManagesOnlyClinicUsers(realm))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("policy types");
    }

    @Test
    void detectsMoreScopesOnClinicUsers() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        ObjectNode config =
                (ObjectNode) named(adminPolicies(realm), "type", "scope").path("config");
        config.put("scopes", "[\"view\",\"view-members\",\"manage-members\",\"manage-membership\",\"manage\"]");

        assertThatThrownBy(() -> assertBackendServiceAccountManagesOnlyClinicUsers(realm))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("scopes on " + CLINIC_USERS);
    }

    @Test
    void detectsAPermissionOnUsers() {
        ObjectNode realm = (ObjectNode) REALM.deepCopy();
        ObjectNode permission = adminPolicies(realm).addObject();
        permission.put("name", "backend-manages-users");
        permission.put("type", "scope");
        permission.putObject("config").put("defaultResourceType", "Users").put("scopes", "[\"manage\"]");

        assertThatThrownBy(() -> assertBackendServiceAccountManagesOnlyClinicUsers(realm))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("permission resource types");
    }

    @Test
    void localClinicUsersAreInClinicUsersAndThePlatformAdminIsNot() {
        assertThat(strings(USERS.path("users"), "username")).contains("plataforma@dentasoft.test");
        assertThat(misgroupedUsers(USERS)).isEmpty();
    }

    @Test
    void detectsMisgroupedLocalUsers() {
        ObjectNode users = (ObjectNode) USERS.deepCopy();
        ((ObjectNode) users.path("users").get(0)).putArray("groups").add(CLINIC_USERS);
        ((ObjectNode) users.path("users").get(1)).remove("groups");

        assertThat(misgroupedUsers(users))
                .containsExactlyInAnyOrder("plataforma@dentasoft.test", "admin@clinica-norte.test");
    }

    @Test
    void usersCannotRegisterThemselvesAndEmailsGoThroughSmtpFromVariables() {
        assertThat(REALM.path("registrationAllowed").asBoolean(true)).isFalse();
        assertThat(REALM.path("verifyEmail").asBoolean()).isTrue();
        assertThat(REALM.path("resetPasswordAllowed").asBoolean()).isTrue();
        assertThat(REALM.path("organizationsEnabled").asBoolean()).isTrue();

        JsonNode smtp = REALM.path("smtpServer");
        for (String key : List.of("host", "port", "from", "auth", "user", "password", "starttls", "ssl")) {
            assertThat(smtp.path(key).asString()).as("smtpServer.%s", key).matches(PLACEHOLDER);
        }
    }

    @Test
    void browserLoginRequiresPasswordAndOtp() {
        JsonNode browser = flow(REALM.path("browserFlow").asString());
        JsonNode forms = flow(named(browser.path("authenticationExecutions"), "autheticatorFlow", "true")
                .path("flowAlias")
                .asString());

        assertThat(requirements(forms))
                .containsExactly(
                        Map.entry("auth-username-password-form", "REQUIRED"), Map.entry("auth-otp-form", "REQUIRED"));
        assertThat(requirements(browser)).containsExactly(Map.entry("auth-cookie", "ALTERNATIVE"));
        assertThat(REALM.path("otpPolicyType").asString()).isEqualTo("totp");
    }

    @Test
    void passwordGrantIsAlwaysDenied() {
        JsonNode directGrant = flow(REALM.path("directGrantFlow").asString());

        assertThat(requirements(directGrant)).containsExactly(Map.entry("deny-access-authenticator", "REQUIRED"));
    }

    @Test
    void tokensAreShortLivedAndRefreshTokensServeOnce() {
        assertThat(REALM.path("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(REALM.path("revokeRefreshToken").asBoolean()).isTrue();
        assertThat(REALM.path("refreshTokenMaxReuse").asInt(-1)).isZero();
        assertThat(REALM.path("ssoSessionIdleTimeout").asInt()).isEqualTo(30 * 60);
        assertThat(REALM.path("ssoSessionMaxLifespan").asInt()).isEqualTo(10 * 60 * 60);
    }

    @Test
    void bruteForceProtectionAndPasswordPolicyAreOn() {
        assertThat(REALM.path("bruteForceProtected").asBoolean()).isTrue();
        assertThat(REALM.path("permanentLockout").asBoolean(true)).isFalse();
        assertThat(REALM.path("failureFactor").asInt()).isEqualTo(5);
        assertThat(REALM.path("waitIncrementSeconds").asInt()).isEqualTo(60);
        assertThat(REALM.path("maxFailureWaitSeconds").asInt()).isEqualTo(15 * 60);
        assertThat(REALM.path("passwordPolicy").asString())
                .contains("length(12)", "maxLength(128)", "notUsername", "notEmail", "passwordHistory(5)");
    }

    @Test
    void securityEventsAreStoredForALimitedTimeWithoutAdminDetails() {
        int ninetyDays = 90 * 24 * 60 * 60;

        assertThat(REALM.path("eventsEnabled").asBoolean()).isTrue();
        assertThat(REALM.path("eventsExpiration").asInt()).isEqualTo(ninetyDays);
        assertThat(REALM.path("adminEventsEnabled").asBoolean()).isTrue();
        assertThat(REALM.path("adminEventsDetailsEnabled").asBoolean(true)).isFalse();
        assertThat(REALM.path("attributes").path("adminEventsExpiration").asString())
                .isEqualTo(String.valueOf(ninetyDays));
        // The jboss-logging listener would write user names and IP addresses to the container log.
        assertThat(strings(REALM.path("eventsListeners"))).isEmpty();
    }

    @Test
    void composeImportsTheRealmAndPassesEveryPlaceholderToKeycloak() {
        Map<String, Object> shared = keycloakService("compose.yml");
        Map<String, Object> local = keycloakService("compose.local.yml");

        assertThat(placeholders(REALM))
                .isNotEmpty()
                .allSatisfy(name -> assertThat(environment(shared))
                        .containsKey(name)
                        .extractingByKey(name)
                        .asString()
                        .startsWith("${" + name));
        assertThat(placeholders(USERS))
                .containsExactly("DENTASOFT_KC_LOCAL_USERS_PASSWORD")
                .allSatisfy(name -> assertThat(environment(local))
                        .containsKey(name)
                        .extractingByKey(name)
                        .asString()
                        .startsWith("${" + name));
        assertThat(list(shared, "volumes")).contains(REALM_MOUNT);
        assertThat(list(local, "command")).contains("--import-realm");
    }

    @Test
    void testUsersAreInventedAndOnlyMountedLocally() {
        assertThat(strings(USERS.path("users"), "email"))
                .isNotEmpty()
                .allSatisfy(email -> assertThat(email).endsWith(".test"));
        assertThat(list(keycloakService("compose.local.yml"), "volumes")).contains(USERS_MOUNT);
        assertThat(readString(INFRA.resolve("compose.yml"))).doesNotContain("keycloak/local");
    }

    /** Confidential, client credentials only, no browser or redirect, and only the token scopes it needs. */
    private static void assertBackendClientHasOnlyAServiceAccount(JsonNode realm) {
        JsonNode backend = client(realm, BACKEND_CLIENT);

        assertThat(backend.path("publicClient").asBoolean(true))
                .as("publicClient")
                .isFalse();
        assertThat(backend.path("serviceAccountsEnabled").asBoolean())
                .as("serviceAccountsEnabled")
                .isTrue();
        for (String flag : List.of(
                "standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled", "fullScopeAllowed")) {
            assertThat(backend.path(flag).asBoolean(true)).as(flag).isFalse();
        }
        for (String grant : List.of("oauth2.device.authorization.grant.enabled", "oidc.ciba.grant.enabled")) {
            assertThat(backend.path("attributes").path(grant).asString())
                    .as(grant)
                    .isEqualTo("false");
        }
        for (String field : List.of("redirectUris", "webOrigins")) {
            assertThat(strings(backend.path(field))).as(field).isEmpty();
        }
        for (String field : List.of("rootUrl", "baseUrl", "adminUrl")) {
            assertThat(backend.has(field)).as(field).isFalse();
        }
        assertThat(backend.path("secret").asString()).as("secret").isEqualTo("${DENTASOFT_KC_BACKEND_CLIENT_SECRET}");
        // kc-admin-api adds the Admin API audience (without it the Admin API answers 401); roles adds resource_access.
        assertThat(strings(backend.path("defaultClientScopes")))
                .as("defaultClientScopes")
                .containsExactlyInAnyOrder("basic", "roles", "kc-admin-api");
        assertThat(strings(backend.path("optionalClientScopes")))
                .as("optionalClientScopes")
                .isEmpty();
    }

    /**
     * The service account has exactly {@link #SERVICE_ACCOUNT_ROLES}, and so do the client scope mappings: with
     * {@code fullScopeAllowed: false}, a role without its scope mapping never reaches the token.
     */
    private static void assertBackendServiceAccountHasOnlyTheMinimalRoles(JsonNode realm) {
        assertThat(strings(realm.path("users"), "username")).as("realm users").containsExactly(SERVICE_ACCOUNT);
        JsonNode account = named(realm.path("users"), "username", SERVICE_ACCOUNT);

        assertThat(account.path("serviceAccountClientId").asString()).isEqualTo(BACKEND_CLIENT);
        assertThat(strings(account.path("realmRoles")))
                .as("service account realm roles")
                .isEmpty();
        assertThat(strings(account.path("groups"))).as("service account groups").isEmpty();
        assertThat(keys(account.path("clientRoles")))
                .as("service account clients")
                .containsExactly("realm-management");
        assertThat(strings(account.path("clientRoles").path("realm-management")))
                .as("service account realm-management roles")
                .containsExactlyInAnyOrderElementsOf(SERVICE_ACCOUNT_ROLES);

        JsonNode mappings = realm.path("clientScopeMappings");
        assertThat(keys(mappings)).as("clientScopeMappings").containsExactly("realm-management");
        assertThat(strings(mappings.path("realm-management"), "client"))
                .as("realm-management scope mappings")
                .containsExactly(BACKEND_CLIENT);
        JsonNode mapping = named(mappings.path("realm-management"), "client", BACKEND_CLIENT);
        assertThat(strings(mapping.path("roles")))
                .as("realm-management scope mappings of %s", BACKEND_CLIENT)
                .containsExactlyInAnyOrderElementsOf(SERVICE_ACCOUNT_ROLES);
        assertThat(strings(realm.path("scopeMappings"), "client"))
                .as("realm role scope mappings")
                .doesNotContain(BACKEND_CLIENT);
    }

    /**
     * Fine-grained admin permissions (FGAP v2): one user policy for the service account and one permission on the
     * members of {@link #CLINIC_USERS}, so it can never see or change a platform administrator. A client policy does
     * not work (user search returns nothing), and the permission names the group by its id, which is why the id is
     * fixed in the file.
     */
    private static void assertBackendServiceAccountManagesOnlyClinicUsers(JsonNode realm) {
        assertThat(realm.path("adminPermissionsEnabled").asBoolean())
                .as("adminPermissionsEnabled")
                .isTrue();
        assertThat(strings(realm.path("groups"), "path")).as("groups").containsExactly(CLINIC_USERS);
        JsonNode group = named(realm.path("groups"), "path", CLINIC_USERS);
        String groupId = group.path("id").asString();
        assertThat(groupId).as("%s id", CLINIC_USERS).matches(UUID_PATTERN);
        // Adding a user to the group must never grant a role.
        for (String field : List.of("realmRoles", "clientRoles", "subGroups")) {
            assertThat(group.path(field).isEmpty())
                    .as("%s %s", CLINIC_USERS, field)
                    .isTrue();
        }

        JsonNode authorization = client(realm, "admin-permissions").path("authorizationSettings");
        assertThat(strings(authorization.path("resources"), "name"))
                .as("resources")
                .contains(groupId);
        JsonNode policies = authorization.path("policies");
        assertThat(StreamSupport.stream(policies.values().spliterator(), false)
                        .map(p -> p.path("config").path("defaultResourceType").asString(""))
                        .toList())
                .as("permission resource types")
                .doesNotContain("Users", "Roles", "Clients");
        assertThat(strings(policies, "type")).as("policy types").containsExactlyInAnyOrder("user", "scope");

        JsonNode policy = named(policies, "type", "user");
        assertThat(jsonStrings(policy.path("config").path("users")))
                .as("users of the policy")
                .containsExactly(SERVICE_ACCOUNT);

        JsonNode permission = named(policies, "type", "scope").path("config");
        assertThat(permission.path("defaultResourceType").asString())
                .as("resource type")
                .isEqualTo("Groups");
        assertThat(jsonStrings(permission.path("resources"))).as("resources").containsExactly(groupId);
        assertThat(jsonStrings(permission.path("scopes")))
                .as("scopes on %s", CLINIC_USERS)
                .containsExactlyInAnyOrderElementsOf(CLINIC_USERS_SCOPES);
        assertThat(jsonStrings(permission.path("applyPolicies")))
                .as("policies of the permission")
                .containsExactly(policy.path("name").asString());
    }

    private static ArrayNode adminPolicies(JsonNode realm) {
        return (ArrayNode)
                client(realm, "admin-permissions").path("authorizationSettings").path("policies");
    }

    /** Local users in the wrong place: a platform administrator in clinic-users, or a clinic user out of it. */
    private static List<String> misgroupedUsers(JsonNode users) {
        List<String> found = new ArrayList<>();
        for (JsonNode user : users.path("users").values()) {
            boolean platformAdmin = strings(user.path("realmRoles")).contains("PLATFORM_ADMIN");
            boolean clinicUser = strings(user.path("groups")).contains(CLINIC_USERS);
            if (platformAdmin == clinicUser) {
                found.add(user.path("username").asString());
            }
        }
        return found;
    }

    /** The clients that receive a client role through {@code clientScopeMappings}. */
    private static List<String> clientScopeMappingClients(JsonNode realm) {
        List<String> clients = new ArrayList<>();
        for (Map.Entry<String, JsonNode> roles :
                realm.path("clientScopeMappings").properties()) {
            clients.addAll(strings(roles.getValue(), "client"));
        }
        return clients;
    }

    private static List<String> keys(JsonNode object) {
        return object.properties().stream().map(Map.Entry::getKey).toList();
    }

    /** A list that Keycloak keeps as a JSON array inside a string, such as the {@code config} of a policy. */
    private static List<String> jsonStrings(JsonNode value) {
        return strings(JSON.readTree(value.asString("[]")));
    }

    /** The {@code keycloak} service of a Compose file of {@code infra/}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> keycloakService(String composeFile) {
        Map<String, Object> compose = new Yaml().load(readString(INFRA.resolve(composeFile)));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return (Map<String, Object>) services.get("keycloak");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(Map<String, Object> service) {
        return (Map<String, Object>) service.getOrDefault("environment", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> service, String key) {
        return (List<Object>) service.getOrDefault(key, List.of());
    }

    /** Every string value with a URL or a project domain, as {@code path: value}. */
    private static List<String> fixedUrls(JsonNode root) {
        List<String> found = new ArrayList<>();
        walk(root, "", (path, key, value) -> {
            if (value.isString() && FIXED_URL.matcher(value.asString()).find()) {
                found.add(path + ": " + value.asString());
            }
        });
        return found;
    }

    /** Wildcards (and Keycloak's {@code +}, "every redirect URI") in redirect URIs and web origins. */
    private static List<String> wildcards(JsonNode realm) {
        List<String> found = new ArrayList<>();
        for (JsonNode client : realm.path("clients").values()) {
            String id = client.path("clientId").asString();
            for (String field : List.of("redirectUris", "webOrigins")) {
                strings(client.path(field)).stream()
                        .filter(KeycloakRealmFileTests::isWildcard)
                        .forEach(uri -> found.add(id + " " + field + ": " + uri));
            }
            String logout =
                    client.path("attributes").path("post.logout.redirect.uris").asString("");
            Arrays.stream(logout.split("##"))
                    .filter(KeycloakRealmFileTests::isWildcard)
                    .forEach(uri -> found.add(id + " post.logout.redirect.uris: " + uri));
        }
        return found;
    }

    private static boolean isWildcard(String uri) {
        return uri.contains("*") || uri.equals("+");
    }

    /** Paths of credential values that are not an environment placeholder, and of exported credential data. */
    private static List<String> secrets(JsonNode root) {
        List<String> found = new ArrayList<>();
        walk(root, "", (path, key, value) -> {
            boolean credentialValue = path.matches(".*credentials\\[\\d+]\\.value");
            boolean secretKey = SECRET_KEYS.contains(key);
            if (EXPORTED_CREDENTIAL_KEYS.contains(key)) {
                found.add(path);
            } else if ((credentialValue || secretKey)
                    && value.isString()
                    && !value.asString().isEmpty()
                    && !PLACEHOLDER.matcher(value.asString()).matches()) {
                found.add(path);
            }
        });
        return found;
    }

    private static List<String> placeholders(JsonNode root) {
        List<String> names = new ArrayList<>();
        walk(root, "", (path, key, value) -> {
            if (value.isString()) {
                Matcher m = PLACEHOLDER.matcher(value.asString());
                while (m.find()) {
                    if (!names.contains(m.group(1))) {
                        names.add(m.group(1));
                    }
                }
            }
        });
        return names;
    }

    @FunctionalInterface
    private interface Visitor {
        void visit(String path, String key, JsonNode value);
    }

    private static void walk(JsonNode node, String path, Visitor visitor) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                visitor.visit(child, entry.getKey(), entry.getValue());
                walk(entry.getValue(), child, visitor);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode element : node.values()) {
                String child = path + "[" + i++ + "]";
                if (!element.isContainer()) {
                    visitor.visit(child, "", element);
                }
                walk(element, child, visitor);
            }
        }
    }

    /** The authenticator and requirement of each execution of a flow that is not a sub-flow, in priority order. */
    private static List<Map.Entry<String, String>> requirements(JsonNode flow) {
        return StreamSupport.stream(
                        flow.path("authenticationExecutions").values().spliterator(), false)
                .filter(e -> !e.path("autheticatorFlow").asBoolean())
                .sorted((a, b) -> Integer.compare(
                        a.path("priority").asInt(), b.path("priority").asInt()))
                .map(e -> Map.entry(
                        e.path("authenticator").asString(),
                        e.path("requirement").asString()))
                .toList();
    }

    private static JsonNode flow(String alias) {
        return named(REALM.path("authenticationFlows"), "alias", alias);
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        return named(realm.path("clients"), "clientId", clientId);
    }

    private static JsonNode named(JsonNode array, String field, String value) {
        for (JsonNode element : array.values()) {
            if (element.path(field).asString().equals(value)) {
                return element;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value);
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.values().spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }

    private static List<String> strings(JsonNode array, String field) {
        return StreamSupport.stream(array.values().spliterator(), false)
                .map(e -> e.path(field).asString())
                .toList();
    }

    private static JsonNode read(Path file) {
        return JSON.readTree(readString(INFRA.resolve(file)));
    }

    /** Reads a file of {@code infra/}, from {@code backend/} (Maven) or from the repository root (some IDEs). */
    private static String readString(Path file) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(file);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(file + " not found from " + Path.of("").toAbsolutePath() + " or its parents");
    }
}
