package es.crisbr.dentasoft.identity.internal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import es.crisbr.dentasoft.shared.DentasoftUrls;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Checks the Keycloak access tokens. The security chains ({@code shared.ApiSecurity}) use these beans.
 *
 * <ul>
 *   <li>Signature: RS256 only, with the realm's keys from {@code dentasoft.auth.jwk-set-uri}. Unsigned tokens ({@code
 *       alg: none}) and any other algorithm are rejected, HMAC included, so a token "signed" with the public key as an
 *       HMAC secret is worthless.
 *   <li>Keys: asked on the first token, never at startup, so the application starts while Keycloak is down. With
 *       short timeouts ({@code jwks-connect-timeout}, {@code jwks-read-timeout}), a slow Keycloak answers a 401 in
 *       seconds instead of holding requests. Cached for five minutes and asked again at most twice every 30 seconds: a
 *       token with an unknown key ID cannot make every request call Keycloak.
 *   <li>Claims: {@code exp} is required, with {@value #CLOCK_SKEW_SECONDS} seconds of clock skew; the issuer must be
 *       {@code {dentasoft.urls.auth}/realms/{realm}}; the audience must include {@value #AUDIENCE}; {@code sub} must be
 *       a UUID, because it is stored in the {@code uuid} audit columns; {@code typ} must be {@value #ACCESS_TOKEN}, so an
 *       ID or refresh token is never taken for an access token, whatever audience the realm gives it.
 *   <li>Authorities: the realm roles Dentasoft knows ({@link RealmRoles}). The user's name is {@code sub}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfiguration {

    static final String AUDIENCE = "dentasoft-api";
    static final long CLOCK_SKEW_SECONDS = 30;

    /** Keycloak's claim for the kind of token: {@value #ACCESS_TOKEN} in access tokens, {@code ID} in ID tokens. */
    static final String TOKEN_TYPE = "typ";

    static final String ACCESS_TOKEN = "Bearer";

    @Bean
    JwtDecoder jwtDecoder(DentasoftUrls urls, AuthProperties properties) throws MalformedURLException {
        int connectTimeout = Math.toIntExact(properties.jwksConnectTimeout().toMillis());
        int readTimeout = Math.toIntExact(properties.jwksReadTimeout().toMillis());
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(
                        URI.create(properties.jwkSetUri()).toURL(),
                        new DefaultResourceRetriever(
                                connectTimeout, readTimeout, JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT))
                // Another request waits for a refresh in progress no longer than the refresh itself can take.
                .cache(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE, connectTimeout + readTimeout + 1_000L)
                .rateLimited(JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL)
                .build();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // The claims are checked by the validators below, with Dentasoft's clock skew, not by Nimbus's defaults.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(issuer(urls, properties)));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }

    static String issuer(DentasoftUrls urls, AuthProperties properties) {
        String auth = urls.auth();
        while (auth.endsWith("/")) {
            auth = auth.substring(0, auth.length() - 1);
        }
        return auth + "/realms/" + properties.realm();
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(CLOCK_SKEW_SECONDS));
        timestamps.setAllowEmptyExpiryClaim(false);
        return JwtValidators.createDefaultWithValidators(List.of(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<@Nullable Object>(JwtClaimNames.SUB, JwtConfiguration::isUuid),
                new JwtClaimValidator<@Nullable Object>(TOKEN_TYPE, ACCESS_TOKEN::equals)));
    }

    /** Only the canonical form, as Keycloak writes it: {@code UUID.fromString} also accepts {@code 1-1-1-1-1}. */
    static boolean isUuid(@Nullable Object subject) {
        if (!(subject instanceof String text)) {
            return false;
        }
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }
}
