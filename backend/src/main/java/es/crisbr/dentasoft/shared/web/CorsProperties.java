package es.crisbr.dentasoft.shared.web;

import java.util.List;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Origins allowed to call the API from a browser ({@code DENTASOFT_CORS_ALLOWED_ORIGINS}, comma separated).
 * Defaults to {@code dentasoft.urls.app}. The CORS filter that uses it arrives with M00-T08.
 */
@Validated
@ConfigurationProperties("dentasoft.cors")
record CorsProperties(List<@URL String> allowedOrigins) {

    CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
