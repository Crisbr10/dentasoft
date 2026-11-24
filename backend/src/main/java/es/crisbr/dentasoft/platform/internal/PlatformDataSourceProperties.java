package es.crisbr.dentasoft.platform.internal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection of the platform module as {@code dentasoft_platform} (ADR 0011). Required: without it the application
 * does not start, as with {@code dentasoft.urls}. The URL defaults to the application's ({@code application.yml}).
 */
@Validated
@ConfigurationProperties("dentasoft.platform.datasource")
record PlatformDataSourceProperties(
        @NotBlank(message = "is required: set DENTASOFT_PLATFORM_DATASOURCE_URL or SPRING_DATASOURCE_URL")
        String url,

        @NotBlank(message = "is required: set the DENTASOFT_PLATFORM_DATASOURCE_USERNAME environment variable")
        String username,

        @NotBlank(message = "is required: set the DENTASOFT_PLATFORM_DATASOURCE_PASSWORD environment variable")
        String password) {

    /** Never prints the password, so it cannot reach a log or an error message. */
    @Override
    public String toString() {
        return "PlatformDataSourceProperties[url=" + url + ", username=" + username + ", password=***]";
    }
}
