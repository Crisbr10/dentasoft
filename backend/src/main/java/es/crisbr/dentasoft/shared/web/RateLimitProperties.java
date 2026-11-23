package es.crisbr.dentasoft.shared.web;

import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Request rate limits, applied per user when the request is authenticated and per client IP otherwise.
 *
 * @param enabled turns the limit off entirely (only meant for troubleshooting); the key is then not required
 * @param anonymousPerMinute requests per minute for each anonymous client IP
 * @param userPerMinute requests per minute for each authenticated user
 * @param key secret for the HMAC that turns IPs and user IDs into Redis keys ({@code DENTASOFT_RATE_LIMIT_KEY}),
 *     so the raw values are never stored
 */
@Validated
@ConfigurationProperties("dentasoft.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("120") @Positive int anonymousPerMinute,
        @DefaultValue("600") @Positive int userPerMinute,
        @Nullable String key) {

    static final int MIN_KEY_LENGTH = 32;

    RateLimitProperties {
        if (enabled && (key == null || key.isBlank())) {
            throw new IllegalArgumentException(
                    "dentasoft.rate-limit.key is required: set the DENTASOFT_RATE_LIMIT_KEY environment variable");
        }
        if (enabled && key.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("dentasoft.rate-limit.key must be at least " + MIN_KEY_LENGTH
                    + " characters long (DENTASOFT_RATE_LIMIT_KEY)");
        }
    }

    @Override
    public String toString() {
        return "RateLimitProperties[enabled=%s, anonymousPerMinute=%d, userPerMinute=%d, key=***]"
                .formatted(enabled, anonymousPerMinute, userPerMinute);
    }
}
