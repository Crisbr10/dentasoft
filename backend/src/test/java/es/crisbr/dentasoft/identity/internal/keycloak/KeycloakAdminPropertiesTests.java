package es.crisbr.dentasoft.identity.internal.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The application refuses to start without the client secret or with settings Keycloak cannot honour. */
class KeycloakAdminPropertiesTests {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final String BASE_URL = "https://auth.dentasoft.test";
    private static final String SECRET = "test-only-keycloak-admin-secret";

    @Test
    void theDefaultsAreValid() {
        assertThat(VALIDATOR.validate(properties(SECRET, Duration.ofHours(72)))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void theClientSecretIsRequired(String secret) {
        assertThat(VALIDATOR.validate(properties(secret, Duration.ofHours(72))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("clientSecret");
    }

    /** Keycloak counts the lifespan in whole seconds; a month-long invitation link is too long to stay unused. */
    @ParameterizedTest
    @CsvSource({"PT0S, false", "PT-1H, false", "PT0.5S, false", "PT1S, true", "P30D, true", "PT720H1S, false"})
    void theInvitationLifespanIsPositiveAndAtMostThirtyDays(Duration lifespan, boolean valid) {
        assertThat(VALIDATOR.validate(properties(SECRET, lifespan)).isEmpty()).isEqualTo(valid);
    }

    @Test
    void theInvitationLifespanIsRequired() {
        assertThat(VALIDATOR.validate(properties(SECRET, null))).isNotEmpty();
    }

    /** A zero timeout means waiting forever for Keycloak. */
    @ParameterizedTest
    @CsvSource({"PT0S, PT5S", "PT2S, PT0S"})
    void timeoutsMustBePositive(Duration connect, Duration read) {
        assertThat(VALIDATOR.validate(new KeycloakAdminProperties(
                        BASE_URL, "dentasoft", "dentasoft-backend", SECRET, connect, read, Duration.ofHours(72))))
                .isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"dentasoft, true", "'dentasoft/../master', false", "'', false"})
    void theRealmIsAPlainName(String realm, boolean valid) {
        assertThat(VALIDATOR
                        .validate(new KeycloakAdminProperties(
                                BASE_URL,
                                realm,
                                "dentasoft-backend",
                                SECRET,
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(5),
                                Duration.ofHours(72)))
                        .isEmpty())
                .isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource({"'', false", "not a url, false", "https://auth.dentasoft.test, true"})
    void theBaseUrlIsAUrl(String baseUrl, boolean valid) {
        assertThat(VALIDATOR
                        .validate(new KeycloakAdminProperties(
                                baseUrl,
                                "dentasoft",
                                "dentasoft-backend",
                                SECRET,
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(5),
                                Duration.ofHours(72)))
                        .isEmpty())
                .isEqualTo(valid);
    }

    /** The properties may end up in a log or a failure report; the secret must not. */
    @Test
    void toStringHidesTheSecret() {
        assertThat(properties(SECRET, Duration.ofHours(72)).toString())
                .doesNotContain(SECRET)
                .contains("dentasoft-backend");
    }

    private static KeycloakAdminProperties properties(String secret, Duration lifespan) {
        return new KeycloakAdminProperties(
                BASE_URL,
                "dentasoft",
                "dentasoft-backend",
                secret,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                lifespan);
    }
}
