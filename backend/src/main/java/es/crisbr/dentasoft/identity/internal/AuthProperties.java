package es.crisbr.dentasoft.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.hibernate.validator.constraints.URL;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the access tokens are checked.
 *
 * @param realm the Keycloak realm ({@code DENTASOFT_AUTH_REALM}); the issuer is {@code
 *     {dentasoft.urls.auth}/realms/{realm}}
 * @param jwkSetUri where Keycloak publishes its signing keys ({@code DENTASOFT_AUTH_JWK_SET_URI}); asked on the first
 *     token, never at startup
 * @param jwksConnectTimeout how long to wait for a connection to {@code jwkSetUri}
 * @param jwksReadTimeout how long to wait for its answer
 */
@Validated
@ConfigurationProperties("dentasoft.auth")
record AuthProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String realm,
        @NotBlank @URL String jwkSetUri,
        // Zero would mean no timeout at all.
        @NotNull @DurationMin(millis = 1) Duration jwksConnectTimeout,
        @NotNull @DurationMin(millis = 1) Duration jwksReadTimeout) {}
