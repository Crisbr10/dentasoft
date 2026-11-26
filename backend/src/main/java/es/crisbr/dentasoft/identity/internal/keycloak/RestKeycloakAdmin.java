package es.crisbr.dentasoft.identity.internal.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import es.crisbr.dentasoft.shared.DentasoftUrls;
import es.crisbr.dentasoft.shared.Logging;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link KeycloakAdmin} over HTTP with Spring's {@link RestClient}. The access token comes from the client credentials
 * grant of {@code dentasoft-backend} and is kept in memory until it expires, for the whole application and not per
 * user: the calls are made by the service account, whoever asks for them. A token Keycloak rejects (401) is dropped,
 * so the next call asks for a new one.
 *
 * <p>Nothing is logged here. Exception messages name the operation and the status only; their causes are copies
 * without messages ({@link Logging#redacted}), because an I/O error repeats the URL, which may hold an email, and an
 * HTTP error repeats the response body.
 */
final class RestKeycloakAdmin implements KeycloakAdmin {

    static final String REGISTRATION_ID = "keycloak-admin";

    /** The client the invitation link signs in to afterwards: the frontend. */
    static final String WEB_CLIENT_ID = "dentasoft-web";

    /** Every call is made by the service account, whatever user is signed in: one cached token for all of them. */
    private static final Authentication SERVICE_ACCOUNT = new AnonymousAuthenticationToken(
            REGISTRATION_ID, REGISTRATION_ID, AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private final RestClient client;
    private final long invitationLifespanSeconds;
    private final String redirectUri;

    RestKeycloakAdmin(KeycloakAdminProperties properties, DentasoftUrls urls) {
        String baseUrl = withoutTrailingSlashes(properties.baseUrl());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requestFactory.setReadTimeout(properties.readTimeout());

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(baseUrl + "/realms/" + properties.realm() + "/protocol/openid-connect/token")
                .build();
        InMemoryOAuth2AuthorizedClientService tokens =
                new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(registration));
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        // As the token client's own RestClient, with the timeouts of the Admin API calls.
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        new InMemoryClientRegistrationRepository(registration), tokens);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(credentials -> credentials.accessTokenResponseClient(tokenClient))
                .build());

        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        interceptor.setPrincipalResolver(request -> SERVICE_ACCOUNT);
        // Drops the cached token on a 401 only; a 403 is the service account's permissions, not the token.
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokens));

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl + "/admin/realms/" + properties.realm())
                .requestInterceptor(interceptor)
                .build();
        this.invitationLifespanSeconds = properties.invitationLifespan().toSeconds();
        // Exactly the redirect URI the realm registers for dentasoft-web: ${DENTASOFT_URLS_APP}/.
        this.redirectUri = withoutTrailingSlashes(urls.app()) + "/";
    }

    @Override
    public Optional<KeycloakUser> findUserByEmail(String email) {
        return call(
                "find user by email",
                client.get()
                        .uri(uri -> uri.path("/users")
                                .queryParam("email", "{email}")
                                .queryParam("exact", "true")
                                .build(email)),
                (status, response) -> {
                    if (status != 200) {
                        throw unexpected("find user by email", status);
                    }
                    UserRepresentation[] users = response.bodyTo(UserRepresentation[].class);
                    return users == null
                            ? Optional.<KeycloakUser>empty()
                            : Arrays.stream(users).findFirst().map(UserRepresentation::toUser);
                });
    }

    @Override
    public Optional<KeycloakUser> findUser(UUID id) {
        return call("find user", client.get().uri("/users/{id}", id), (status, response) -> switch (status) {
            case 200 ->
                Optional.ofNullable(response.bodyTo(UserRepresentation.class)).map(UserRepresentation::toUser);
            // 403: outside the service account's group, as a platform administrator; as good as not there.
            case 403, 404 -> Optional.empty();
            default -> throw unexpected("find user", status);
        });
    }

    @Override
    public UUID createUser(String email, String firstName, String lastName) {
        NewUser user = new NewUser(
                email, email, firstName, lastName, true, false, List.of(CLINIC_USERS_GROUP), INVITATION_ACTIONS);
        return call(
                "create user",
                client.post()
                        .uri("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(user),
                (status, response) -> switch (status) {
                    case 201 ->
                        UUID.fromString(
                                createdId("create user", response.getHeaders().getLocation()));
                    case 409 -> throw new KeycloakConflictException("Keycloak answered 409 to create user");
                    default -> throw unexpected("create user", status);
                });
    }

    @Override
    public void sendActionsEmail(UUID userId) {
        call(
                "send actions email",
                client.put()
                        .uri(uri -> uri.path("/users/{id}/execute-actions-email")
                                .queryParam("lifespan", "{lifespan}")
                                .queryParam("client_id", "{clientId}")
                                .queryParam("redirect_uri", "{redirectUri}")
                                .build(userId, invitationLifespanSeconds, WEB_CLIENT_ID, redirectUri))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(INVITATION_ACTIONS),
                (status, response) -> expect("send actions email", status, 204));
    }

    @Override
    public void logout(UUID userId) {
        call(
                "log out",
                client.post().uri("/users/{id}/logout", userId),
                (status, response) -> expect("log out", status, 204));
    }

    @Override
    public String ensureOrganization(UUID clinicId) {
        String alias = clinicId.toString();
        Optional<String> existing = findOrganization(alias);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return createOrganization(alias);
        } catch (KeycloakConflictException createdMeanwhile) {
            // Another instance created it between the lookup and the creation.
            return findOrganization(alias)
                    .orElseThrow(() ->
                            new KeycloakAdminException("Keycloak answered 409 to create organization but has none"));
        }
    }

    @Override
    public void addOrganizationMember(String organizationId, UUID userId) {
        call(
                "add organization member",
                client.post()
                        .uri("/organizations/{organizationId}/members", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Keycloak expects the user id as a JSON string; a UUID needs no escaping.
                        .body("\"" + userId + "\""),
                (status, response) -> switch (status) {
                    // 409: already a member.
                    case 201, 204, 409 -> null;
                    default -> throw unexpected("add organization member", status);
                });
    }

    @Override
    public void removeOrganizationMember(String organizationId, UUID userId) {
        call(
                "remove organization member",
                client.delete().uri("/organizations/{organizationId}/members/{userId}", organizationId, userId),
                (status, response) -> switch (status) {
                    // 404: not a member any more.
                    case 204, 404 -> null;
                    default -> throw unexpected("remove organization member", status);
                });
    }

    @Override
    public boolean belongsToAnyOrganization(UUID userId) {
        return call(
                "find user organizations",
                client.get().uri("/organizations/members/{userId}/organizations", userId),
                (status, response) -> switch (status) {
                    case 200 -> {
                        OrganizationRepresentation[] organizations =
                                response.bodyTo(OrganizationRepresentation[].class);
                        yield organizations != null && organizations.length > 0;
                    }
                    // No such user: a member of nothing.
                    case 404 -> false;
                    default -> throw unexpected("find user organizations", status);
                });
    }

    private Optional<String> findOrganization(String alias) {
        return call(
                "find organization",
                client.get()
                        .uri(uri -> uri.path("/organizations")
                                .queryParam("search", "{alias}")
                                .queryParam("exact", "true")
                                .build(alias)),
                (status, response) -> {
                    if (status != 200) {
                        throw unexpected("find organization", status);
                    }
                    OrganizationRepresentation[] organizations = response.bodyTo(OrganizationRepresentation[].class);
                    return organizations == null
                            ? Optional.<String>empty()
                            : Arrays.stream(organizations)
                                    .filter(organization -> alias.equals(organization.alias()))
                                    .map(OrganizationRepresentation::id)
                                    .findFirst();
                });
    }

    private String createOrganization(String alias) {
        return call(
                "create organization",
                client.post()
                        .uri("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new NewOrganization(alias, alias)),
                (status, response) -> switch (status) {
                    case 201 ->
                        createdId("create organization", response.getHeaders().getLocation());
                    case 409 -> throw new KeycloakConflictException("Keycloak answered 409 to create organization");
                    default -> throw unexpected("create organization", status);
                });
    }

    /** Runs the request and hands the answer to {@code handler}, turning every failure into this API's exceptions. */
    private static <T> T call(String operation, RestClient.RequestHeadersSpec<?> request, Handler<T> handler) {
        try {
            return request.exchange((ignored, response) ->
                    handler.handle(response.getStatusCode().value(), response));
        } catch (KeycloakAdminException | KeycloakUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new KeycloakUnavailableException("Keycloak did not answer to " + operation, Logging.redacted(e));
        } catch (OAuth2AuthorizationException e) {
            throw tokenFailure(operation, e);
        } catch (RestClientException | HttpMessageConversionException | IllegalArgumentException e) {
            throw new KeycloakAdminException("Unexpected answer from Keycloak to " + operation, Logging.redacted(e));
        }
    }

    /**
     * No access token. Keycloak down or slow is an outage; anything else, such as a wrong client secret, is a
     * configuration error that trying again will not fix.
     */
    private static RuntimeException tokenFailure(String operation, OAuth2AuthorizationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException
                    || cause instanceof HttpServerErrorException
                    || cause instanceof IOException) {
                return new KeycloakUnavailableException(
                        "Keycloak gave no access token for " + operation, Logging.redacted(e));
            }
        }
        int status = 0;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) {
                status = response.getStatusCode().value();
            }
        }
        return new KeycloakAdminException(
                "Keycloak refused the access token for " + operation + " (" + (status == 0 ? "" : status + ", ")
                        + e.getError().getErrorCode() + ")",
                Logging.redacted(e));
    }

    private static @Nullable Void expect(String operation, int status, int expected) {
        if (status != expected) {
            throw unexpected(operation, status);
        }
        return null;
    }

    private static RuntimeException unexpected(String operation, int status) {
        String message = "Keycloak answered " + status + " to " + operation;
        return status >= 500 ? new KeycloakUnavailableException(message, null) : new KeycloakAdminException(message);
    }

    /** The id at the end of the {@code Location} of a 201. */
    private static String createdId(String operation, @Nullable URI location) {
        String path = location == null ? null : location.getPath();
        if (path == null || path.endsWith("/") || path.lastIndexOf('/') < 0) {
            throw new KeycloakAdminException("Keycloak answered 201 without a location to " + operation);
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String withoutTrailingSlashes(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @FunctionalInterface
    private interface Handler<T> {
        T handle(int status, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException;
    }

    /** The fields of Keycloak's UserRepresentation this API reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserRepresentation(
            String id,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable Boolean enabled,
            @Nullable Boolean emailVerified,
            @Nullable List<String> requiredActions) {

        KeycloakUser toUser() {
            return new KeycloakUser(
                    UUID.fromString(id),
                    email,
                    firstName,
                    lastName,
                    Boolean.TRUE.equals(enabled),
                    Boolean.TRUE.equals(emailVerified),
                    requiredActions == null ? List.of() : requiredActions);
        }

        @Override
        public String toString() {
            return "UserRepresentation[id=" + id + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrganizationRepresentation(
            String id, @Nullable String alias) {}

    private record NewUser(
            String username,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            boolean emailVerified,
            List<String> groups,
            List<String> requiredActions) {

        @Override
        public String toString() {
            return "NewUser[groups=" + groups + "]";
        }
    }

    private record NewOrganization(String name, String alias) {}
}
