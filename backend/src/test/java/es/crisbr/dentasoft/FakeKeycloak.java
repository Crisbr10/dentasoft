package es.crisbr.dentasoft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-process stand-in for the parts of Keycloak's token endpoint and Admin REST API that the backend uses, so the
 * tests need no real Keycloak. A local HTTP server on a free port of the loopback interface ({@link #baseUrl()}) with
 * an in-memory realm {@value #REALM}, which behaves as the real one does for the service account of {@value
 * #CLIENT_ID} with its fine-grained permissions (FGAP v2):
 *
 * <ul>
 *   <li>Token endpoint ({@value #TOKEN_PATH}): the client credentials grant with {@value #CLIENT_ID} and {@value
 *       #CLIENT_SECRET}, in the form or as HTTP Basic. Every other request gets 401 or 400.
 *   <li>Admin API (under {@value #ADMIN_PATH}): only with a token it issued, 401 otherwise. Users are created only into
 *       {@value #CLINIC_USERS_GROUP} (403 otherwise); a duplicate email or username answers 409. Organizations have a
 *       unique name and alias (409 on a duplicate); adding a member twice answers 409 and removing a non-member 404.
 *   <li>Invisible users ({@link #addInvisibleUser(String)}), as a platform administrator is to the service account:
 *       searches do not return them, reading or touching them answers 403 and creating another user with their email
 *       409.
 * </ul>
 *
 * <p>Integration tests share one server ({@link #shared()}, published by {@link FakeKeycloakConfiguration}) and call
 * {@link #reset()} before each test; unit tests may {@link #start()} their own. Faults are injected with {@link
 * #failNext(String, String, int)}, {@link #delayNext(String, String, Duration)} and {@link #beforeNext(String, String,
 * Runnable)}. Error bodies repeat the request,
 * email included, so tests can check that the client never passes a body on.
 */
public final class FakeKeycloak implements AutoCloseable {

    public static final String REALM = "dentasoft";
    public static final String CLIENT_ID = "dentasoft-backend";

    /** Not a secret: it only opens this fake. */
    public static final String CLIENT_SECRET = "test-only-keycloak-admin-secret";

    public static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";
    public static final String ADMIN_PATH = "/admin/realms/" + REALM;
    public static final String CLINIC_USERS_GROUP = "/clinic-users";

    /** Lifetime of the tokens it issues, as Keycloak's default. */
    public static final int TOKEN_LIFETIME_SECONDS = 300;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final Map<UUID, StoredUser> users = new LinkedHashMap<>();
    private final Map<String, StoredOrganization> organizations = new LinkedHashMap<>();
    private final Set<String> tokens = new HashSet<>();
    private final List<ActionsEmail> actionsEmails = new ArrayList<>();
    private final List<UUID> logouts = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<Fault> faults = new ArrayList<>();

    private FakeKeycloak() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A delayed answer must not hold the others.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    /** The server the integration tests share, started on first use and kept for the whole test run. */
    public static FakeKeycloak shared() {
        return Shared.INSTANCE;
    }

    /** A new server of its own, to {@link #close()} after use. */
    public static FakeKeycloak start() {
        return new FakeKeycloak();
    }

    /** What {@code dentasoft.keycloak-admin.base-url} must be, without a trailing slash. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Forgets every user, organization, record and pending fault. Tokens already issued stay valid, as in Keycloak: the
     * application caches its token across tests, and {@link #revokeTokens()} is there for tests about rejected ones.
     */
    public synchronized void reset() {
        tokenRequests.set(0);
        users.clear();
        organizations.clear();
        actionsEmails.clear();
        logouts.clear();
        requests.clear();
        faults.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- State the tests set up ---

    /** A user of {@value #CLINIC_USERS_GROUP}, as if invited before. */
    public synchronized UUID addUser(
            String email, String firstName, String lastName, boolean emailVerified, List<String> requiredActions) {
        StoredUser user = new StoredUser(UUID.randomUUID(), email.toLowerCase(Locale.ROOT), false);
        user.firstName = firstName;
        user.lastName = lastName;
        user.emailVerified = emailVerified;
        user.requiredActions = new ArrayList<>(requiredActions);
        user.groups = List.of(CLINIC_USERS_GROUP);
        users.put(user.id, user);
        return user.id;
    }

    /** A user the service account cannot see, as a platform administrator. */
    public synchronized UUID addInvisibleUser(String email) {
        StoredUser user = new StoredUser(UUID.randomUUID(), email.toLowerCase(Locale.ROOT), true);
        user.firstName = "Platform";
        user.lastName = "Admin";
        user.emailVerified = true;
        users.put(user.id, user);
        return user.id;
    }

    /** As if the user had followed the invitation link: email verified and nothing left to do. */
    public synchronized void completeInvitation(UUID userId) {
        StoredUser user = existing(userId);
        user.emailVerified = true;
        user.requiredActions = new ArrayList<>();
    }

    /** An organization whose name and alias are both {@code alias}; returns its id. */
    public synchronized String addOrganization(String alias) {
        StoredOrganization organization =
                new StoredOrganization(UUID.randomUUID().toString(), alias, alias);
        organizations.put(organization.id, organization);
        return organization.id;
    }

    public synchronized void addMember(String organizationId, UUID userId) {
        organizations.get(organizationId).members.add(userId);
    }

    // --- What the tests check ---

    public synchronized Optional<User> user(UUID id) {
        return Optional.ofNullable(users.get(id)).map(StoredUser::snapshot);
    }

    public synchronized Optional<User> userByEmail(String email) {
        return users.values().stream()
                .filter(user -> user.email.equals(email.toLowerCase(Locale.ROOT)))
                .findFirst()
                .map(StoredUser::snapshot);
    }

    /** Every user, invisible ones included. */
    public synchronized List<User> users() {
        return users.values().stream().map(StoredUser::snapshot).toList();
    }

    public synchronized Optional<Organization> organizationByAlias(String alias) {
        return organizations.values().stream()
                .filter(organization -> organization.alias.equals(alias))
                .findFirst()
                .map(StoredOrganization::snapshot);
    }

    public synchronized List<Organization> organizations() {
        return organizations.values().stream().map(StoredOrganization::snapshot).toList();
    }

    public synchronized List<ActionsEmail> actionsEmails() {
        return List.copyOf(actionsEmails);
    }

    public synchronized List<UUID> logouts() {
        return List.copyOf(logouts);
    }

    /** How many tokens were asked for, refused ones included. */
    public int tokenRequests() {
        return tokenRequests.get();
    }

    /** The Admin API requests received, in order, faulty ones included; not the token requests. */
    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    // --- Faults ---

    /**
     * The next request with this method whose path starts with {@code pathPrefix} answers {@code status} instead.
     * The prefix is relative to {@value #ADMIN_PATH} ({@code /users}, {@code /organizations}), or {@value
     * #TOKEN_PATH} for the token endpoint.
     */
    public synchronized void failNext(String method, String pathPrefix, int status) {
        faults.add(new Fault(method, pathPrefix, status, Duration.ZERO, null));
    }

    /** The next matching request (as in {@link #failNext}) waits {@code delay} before it is served. */
    public synchronized void delayNext(String method, String pathPrefix, Duration delay) {
        faults.add(new Fault(method, pathPrefix, 0, delay, null));
    }

    /**
     * Runs {@code action} just before the next matching request (as in {@link #failNext}) is served, as another
     * instance of the backend would; for races such as two clinics' first invitations creating the same organization.
     */
    public synchronized void beforeNext(String method, String pathPrefix, Runnable action) {
        faults.add(new Fault(method, pathPrefix, 0, Duration.ZERO, action));
    }

    /** Every token issued so far stops working, as after a revocation. */
    public synchronized void revokeTokens() {
        tokens.clear();
    }

    // --- Records ---

    public record User(
            UUID id,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            boolean emailVerified,
            List<String> requiredActions,
            List<String> groups) {}

    public record Organization(String id, String name, String alias, Set<UUID> members) {}

    /** An {@code execute-actions-email} call: what Keycloak would have emailed. */
    public record ActionsEmail(
            UUID userId, List<String> actions, long lifespanSeconds, String clientId, String redirectUri) {}

    /** An Admin API request; {@code path} is relative to {@value #ADMIN_PATH} and the query is decoded. */
    public record Request(String method, String path, Map<String, String> query, String body) {}

    // --- Server ---

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
            Response response;
            if (path.equals(TOKEN_PATH)) {
                response = token(exchange, method, path, body);
            } else if (path.startsWith(ADMIN_PATH + "/")) {
                response = admin(exchange, method, path.substring(ADMIN_PATH.length()), query, body);
            } else {
                response = Response.status(404);
            }
            send(exchange, response);
        }
    }

    private Response token(HttpExchange exchange, String method, String path, String body) {
        tokenRequests.incrementAndGet();
        Fault fault = takeFault(method, path);
        if (fault != null && fault.status == 0) {
            sleep(fault.delay);
            if (fault.action != null) {
                fault.action.run();
            }
        } else if (fault != null) {
            return Response.error(fault.status, path);
        }
        Map<String, String> form = parse(body);
        String clientId = form.get("client_id");
        String clientSecret = form.get("client_secret");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(
                            Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8)
                    .split(":", 2);
            clientId = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
            clientSecret = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8);
        }
        if (!"POST".equals(method)) {
            return Response.status(405);
        }
        if (!CLIENT_ID.equals(clientId) || !CLIENT_SECRET.equals(clientSecret)) {
            return Response.json(401, Map.of("error", "invalid_client"));
        }
        if (!"client_credentials".equals(form.get("grant_type"))) {
            return Response.json(400, Map.of("error", "unsupported_grant_type"));
        }
        String token = UUID.randomUUID().toString();
        synchronized (this) {
            tokens.add(token);
        }
        return Response.json(
                200,
                Map.of(
                        "access_token",
                        token,
                        "expires_in",
                        TOKEN_LIFETIME_SECONDS,
                        "token_type",
                        "Bearer",
                        "scope",
                        "profile email"));
    }

    private Response admin(HttpExchange exchange, String method, String path, Map<String, String> query, String body) {
        Fault fault;
        synchronized (this) {
            requests.add(new Request(method, path, query, body));
            fault = takeFault(method, path);
        }
        if (fault != null && fault.status == 0) {
            sleep(fault.delay);
            if (fault.action != null) {
                fault.action.run();
            }
        } else if (fault != null) {
            return Response.error(
                    fault.status, path + "?" + exchange.getRequestURI().getRawQuery() + " " + body);
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (this) {
            if (authorization == null
                    || !authorization.startsWith("Bearer ")
                    || !tokens.contains(authorization.substring(7))) {
                return Response.status(401);
            }
            try {
                return route(method, List.of(path.substring(1).split("/")), query, body);
            } catch (RuntimeException badRequest) {
                // Not JSON, or not the JSON Keycloak expects.
                return Response.status(400);
            }
        }
    }

    /** Called with the lock held. */
    private Response route(String method, List<String> segments, Map<String, String> query, String body) {
        String resource = segments.getFirst();
        int size = segments.size();
        if (resource.equals("users")) {
            if (size == 1) {
                return switch (method) {
                    case "GET" -> searchUsers(query);
                    case "POST" -> createUser(body);
                    default -> Response.status(405);
                };
            }
            StoredUser user = parseUuid(segments.get(1)).map(users::get).orElse(null);
            if (user == null) {
                return Response.status(404);
            }
            if (user.invisible) {
                return Response.status(403);
            }
            if (size == 2 && method.equals("GET")) {
                return Response.json(200, user.representation());
            }
            if (size == 3 && segments.get(2).equals("execute-actions-email") && method.equals("PUT")) {
                return actionsEmail(user, query, body);
            }
            if (size == 3 && segments.get(2).equals("logout") && method.equals("POST")) {
                logouts.add(user.id);
                return Response.status(204);
            }
            return Response.status(404);
        }
        if (resource.equals("organizations")) {
            if (size == 1) {
                return switch (method) {
                    case "GET" -> searchOrganizations(query);
                    case "POST" -> createOrganization(body);
                    default -> Response.status(405);
                };
            }
            if (size == 4
                    && segments.get(1).equals("members")
                    && segments.get(3).equals("organizations")) {
                UUID userId = parseUuid(segments.get(2)).orElse(null);
                StoredUser user = userId == null ? null : users.get(userId);
                if (user == null) {
                    return Response.status(404);
                }
                if (user.invisible) {
                    return Response.status(403);
                }
                return Response.json(
                        200,
                        organizations.values().stream()
                                .filter(organization -> organization.members.contains(userId))
                                .map(StoredOrganization::representation)
                                .toList());
            }
            StoredOrganization organization = organizations.get(segments.get(1));
            if (organization == null) {
                return Response.status(404);
            }
            if (size == 3 && segments.get(2).equals("members") && method.equals("POST")) {
                return addMember(organization, body);
            }
            if (size == 4 && segments.get(2).equals("members") && method.equals("DELETE")) {
                UUID userId = parseUuid(segments.get(3)).orElse(null);
                return userId != null && organization.members.remove(userId)
                        ? Response.status(204)
                        : Response.status(404);
            }
            return Response.status(404);
        }
        return Response.status(404);
    }

    private Response searchUsers(Map<String, String> query) {
        String email = query.get("email");
        boolean exact = "true".equals(query.get("exact"));
        return Response.json(
                200,
                users.values().stream()
                        .filter(user -> !user.invisible)
                        .filter(user -> email == null
                                || (exact
                                        ? user.email.equals(email.toLowerCase(Locale.ROOT))
                                        : user.email.contains(email.toLowerCase(Locale.ROOT))))
                        .map(StoredUser::representation)
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private Response createUser(String body) {
        Map<String, Object> representation = JSON.readValue(body, Map.class);
        String email = ((String) representation.get("email")).toLowerCase(Locale.ROOT);
        String username = ((String) representation.get("username")).toLowerCase(Locale.ROOT);
        List<String> groups = (List<String>) representation.getOrDefault("groups", List.of());
        if (!groups.equals(List.of(CLINIC_USERS_GROUP))) {
            return Response.status(403);
        }
        if (users.values().stream().anyMatch(user -> user.email.equals(email) || user.email.equals(username))) {
            return Response.json(409, Map.of("errorMessage", "User exists with same email", "field", email));
        }
        StoredUser user = new StoredUser(UUID.randomUUID(), email, false);
        user.firstName = (String) representation.get("firstName");
        user.lastName = (String) representation.get("lastName");
        user.enabled = Boolean.TRUE.equals(representation.get("enabled"));
        user.emailVerified = Boolean.TRUE.equals(representation.get("emailVerified"));
        user.requiredActions =
                new ArrayList<>((List<String>) representation.getOrDefault("requiredActions", List.of()));
        user.groups = List.copyOf(groups);
        users.put(user.id, user);
        return Response.created(baseUrl() + ADMIN_PATH + "/users/" + user.id);
    }

    @SuppressWarnings("unchecked")
    private Response actionsEmail(StoredUser user, Map<String, String> query, String body) {
        List<String> actions = JSON.readValue(body, List.class);
        long lifespan = Long.parseLong(query.getOrDefault("lifespan", "43200"));
        actionsEmails.add(new ActionsEmail(
                user.id, List.copyOf(actions), lifespan, query.get("client_id"), query.get("redirect_uri")));
        return Response.status(204);
    }

    private Response searchOrganizations(Map<String, String> query) {
        String search = query.get("search");
        boolean exact = "true".equals(query.get("exact"));
        return Response.json(
                200,
                organizations.values().stream()
                        .filter(organization -> search == null
                                || (exact ? organization.name.equals(search) : organization.name.contains(search)))
                        .map(StoredOrganization::representation)
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private Response createOrganization(String body) {
        Map<String, Object> representation = JSON.readValue(body, Map.class);
        String name = (String) representation.get("name");
        String alias = (String) representation.get("alias");
        if (name == null || alias == null) {
            return Response.status(400);
        }
        if (organizations.values().stream()
                .anyMatch(organization -> organization.name.equals(name) || organization.alias.equals(alias))) {
            return Response.json(409, Map.of("errorMessage", "A organization with the same name already exists."));
        }
        StoredOrganization organization =
                new StoredOrganization(UUID.randomUUID().toString(), name, alias);
        organizations.put(organization.id, organization);
        return Response.created(baseUrl() + ADMIN_PATH + "/organizations/" + organization.id);
    }

    private Response addMember(StoredOrganization organization, String body) {
        String id;
        try {
            // Keycloak expects the user id as a JSON string.
            id = JSON.readValue(body, String.class);
        } catch (RuntimeException notAJsonString) {
            return Response.status(400);
        }
        StoredUser user = parseUuid(id).map(users::get).orElse(null);
        if (user == null) {
            return Response.status(404);
        }
        if (user.invisible) {
            return Response.status(403);
        }
        return organization.members.add(user.id) ? Response.status(201) : Response.status(409);
    }

    /** The first pending fault for the request, removed; whoever delays sleeps without the lock. */
    private synchronized @Nullable Fault takeFault(String method, String path) {
        for (Fault fault : faults) {
            if (fault.method.equals(method) && path.startsWith(fault.pathPrefix)) {
                faults.remove(fault);
                return fault;
            }
        }
        return null;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        response.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        if (response.body.length == 0) {
            exchange.sendResponseHeaders(response.status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status, response.body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body);
        }
    }

    private static Map<String, String> parse(@Nullable String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static Optional<UUID> parseUuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    private StoredUser existing(UUID id) {
        StoredUser user = users.get(id);
        if (user == null) {
            throw new IllegalArgumentException("No user " + id);
        }
        return user;
    }

    private record Fault(
            String method,
            String pathPrefix,
            int status,
            Duration delay,
            @Nullable Runnable action) {}

    private record Response(int status, Map<String, String> headers, byte[] body) {

        static Response status(int status) {
            return new Response(status, Map.of(), new byte[0]);
        }

        static Response json(int status, Object body) {
            return new Response(status, Map.of(), JSON.writeValueAsBytes(body));
        }

        static Response created(String location) {
            return new Response(201, Map.of("Location", location), new byte[0]);
        }

        /** An injected failure, whose body repeats the request as a careless server might. */
        static Response error(int status, String request) {
            return json(status, Map.of("error", "injected failure", "request", request));
        }
    }

    private static final class StoredUser {

        final UUID id;
        final String email;
        final boolean invisible;
        String firstName = "";
        String lastName = "";
        boolean enabled = true;
        boolean emailVerified;
        List<String> requiredActions = new ArrayList<>();
        List<String> groups = List.of();

        StoredUser(UUID id, String email, boolean invisible) {
            this.id = id;
            this.email = email;
            this.invisible = invisible;
        }

        User snapshot() {
            return new User(
                    id,
                    email,
                    firstName,
                    lastName,
                    enabled,
                    emailVerified,
                    List.copyOf(requiredActions),
                    List.copyOf(groups));
        }

        Map<String, Object> representation() {
            Map<String, Object> representation = new LinkedHashMap<>();
            representation.put("id", id.toString());
            representation.put("username", email);
            representation.put("email", email);
            representation.put("firstName", firstName);
            representation.put("lastName", lastName);
            representation.put("enabled", enabled);
            representation.put("emailVerified", emailVerified);
            representation.put("requiredActions", requiredActions);
            representation.put("createdTimestamp", 1_700_000_000_000L);
            return representation;
        }
    }

    private static final class StoredOrganization {

        final String id;
        final String name;
        final String alias;
        final Set<UUID> members = new LinkedHashSet<>();

        StoredOrganization(String id, String name, String alias) {
            this.id = id;
            this.name = name;
            this.alias = alias;
        }

        Organization snapshot() {
            return new Organization(id, name, alias, Set.copyOf(members));
        }

        Map<String, Object> representation() {
            return Map.of("id", id, "name", name, "alias", alias, "enabled", true);
        }
    }

    private static final class Shared {
        static final FakeKeycloak INSTANCE = new FakeKeycloak();
    }
}
