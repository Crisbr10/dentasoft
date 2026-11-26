package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The application refuses to start with settings that would weaken the token checks. */
class AuthPropertiesTests {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final String JWKS = "https://auth.dentasoft.test/realms/dentasoft/protocol/openid-connect/certs";

    /** A zero timeout means waiting forever for Keycloak. */
    @ParameterizedTest
    @CsvSource({"PT0S, PT3S", "PT2S, PT0S", "PT-1S, PT3S"})
    void timeoutsMustBePositive(Duration connect, Duration read) {
        assertThat(VALIDATOR.validate(new AuthProperties("dentasoft", JWKS, connect, read)))
                .isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"dentasoft, true", "'dentasoft/../master', false", "'', false"})
    void theRealmIsAPlainName(String realm, boolean valid) {
        assertThat(VALIDATOR
                        .validate(new AuthProperties(realm, JWKS, Duration.ofSeconds(2), Duration.ofSeconds(3)))
                        .isEmpty())
                .isEqualTo(valid);
    }
}
