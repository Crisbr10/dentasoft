package es.crisbr.dentasoft.identity.internal.keycloak;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.hibernate.validator.constraints.URL;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the backend reaches the Keycloak Admin REST API with its own confidential client ({@code dentasoft-backend})
 * and the client credentials grant. Never the admin of the master realm.
 *
 * @param baseUrl where the backend reaches Keycloak ({@code DENTASOFT_KEYCLOAK_ADMIN_BASE_URL}); by default {@code
 *     dentasoft.urls.auth}. The token endpoint is {@code {baseUrl}/realms/{realm}/protocol/openid-connect/token} and
 *     the Admin API {@code {baseUrl}/admin/realms/{realm}}
 * @param realm the realm of the clinic users; by default {@code dentasoft.auth.realm}
 * @param clientId the confidential client with the service account ({@code DENTASOFT_KEYCLOAK_ADMIN_CLIENT_ID})
 * @param clientSecret its secret ({@code DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET}); required, so the application does
 *     not start without it. Never printed: {@link #toString()} leaves it out
 * @param connectTimeout how long to wait for a connection to Keycloak
 * @param readTimeout how long to wait for its answer
 * @param invitationLifespan how long the link of an invitation email stays valid: Keycloak counts it in whole seconds,
 *     at least one and at most 30 days
 */
@Validated
@ConfigurationProperties("dentasoft.keycloak-admin")
public record KeycloakAdminProperties(
        @NotBlank @URL String baseUrl,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String realm,
        @NotBlank String clientId,

        @NotBlank(message = "is required: set the DENTASOFT_KEYCLOAK_ADMIN_CLIENT_SECRET environment variable")
        String clientSecret,
        // Zero would mean no timeout at all.
        @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) Duration readTimeout,

        @NotNull @DurationMin(seconds = 1) @DurationMax(days = 30)
        Duration invitationLifespan) {

    @Override
    public String toString() {
        return "KeycloakAdminProperties[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
                + ", clientSecret=[hidden], connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", invitationLifespan=" + invitationLifespan + "]";
    }
}
