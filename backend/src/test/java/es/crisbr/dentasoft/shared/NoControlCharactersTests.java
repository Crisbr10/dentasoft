package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Names keep their letters, spaces and symbols; control characters, U+0000 among them, are refused. */
class NoControlCharactersTests {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Named(@NoControlCharacters String name) {}

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    /** U+2003 and U+3000 are Unicode spaces, not control characters. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Gabinete 1", "Clínica Prueba Norte", "Ñandú & Cía.", " Gabinete　"})
    void acceptsTextWithoutControlCharacters(String name) {
        assertThat(validator.validate(new Named(name))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Gabinete\u0000", "Gabinete\n1", "\tGabinete", "Gabinete\u007F", "Gabinete\u0085"})
    void refusesControlCharacters(String name) {
        assertThat(validator.validate(new Named(name)))
                .singleElement()
                .extracting(ConstraintViolation::getMessageTemplate)
                .isEqualTo("{es.crisbr.dentasoft.shared.NoControlCharacters.message}");
    }
}
