package es.crisbr.dentasoft.shared;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Public URLs of the platform. Domains change per environment, so they are never written in the code:
 * outside the {@code local} profile they must come from environment variables, and the application refuses
 * to start if any of them is missing or invalid.
 *
 * @param app frontend URL ({@code DENTASOFT_URLS_APP})
 * @param api backend URL ({@code DENTASOFT_URLS_API})
 * @param auth Keycloak URL ({@code DENTASOFT_URLS_AUTH})
 */
@Validated
@ConfigurationProperties("dentasoft.urls")
public record DentasoftUrls(
        @NotBlank(message = "is required: set the DENTASOFT_URLS_APP environment variable") @URL String app,
        @NotBlank(message = "is required: set the DENTASOFT_URLS_API environment variable") @URL String api,
        @NotBlank(message = "is required: set the DENTASOFT_URLS_AUTH environment variable") @URL String auth) {}
