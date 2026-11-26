package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

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
