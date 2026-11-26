package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Addresses with a top-level domain and plain ASCII pass; what {@code @Email} lets through but mail cannot use fails. */
class StrictEmailTests {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Contact(@StrictEmail String email) {}

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "ana@clinica.test",
                "ana.garcia+citas@clinica-norte.example.es",
                "o'brien@example.org",
                "A1!#$%&'*+/=?^_`{|}~-@sub.example.com",
                "ana@xn--clnica-0ta.xn--p1ai"
            })
    void acceptsDeliverableAddresses(String email) {
        assertThat(validator.validate(new Contact(email))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "ana",
                "ana@",
                "@clinica.test",
                "ana@localhost",
                "ana@clinica",
                "ana@clinica.t",
                "ana@clinica.123",
                "ana@[192.168.1.1]",
                "ana@192.168.1.1",
                "\"ana garcia\"@clinica.test",
                "ana garcia@clinica.test",
                ".ana@clinica.test",
                "ana.@clinica.test",
                "ana..garcia@clinica.test",
                "ana@-clinica.test",
                "ana@clinica-.test",
                "ana@clinica..test",
                "ana@clinica.test.",
                "ana@@clinica.test",
                "ana(comentario)@clinica.test",
                "anà@clinica.test",
                "ana@clínica.test",
                "ana@clinica.test\n",
                "ana\u0000@clinica.test"
            })
    void refusesAddressesMailCannotUse(String email) {
        assertThat(validator.validate(new Contact(email)))
                .singleElement()
                .extracting(ConstraintViolation::getMessageTemplate)
                .isEqualTo("{es.crisbr.dentasoft.shared.StrictEmail.message}");
    }

    @Test
    void limitsTheLengthOfTheAddressAndOfItsLocalPart() {
        String local64 = "a".repeat(64);
        String domain252 = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + "." + "e".repeat(57) + ".es";

        assertThat(validator.validate(new Contact(local64 + "@clinica.test"))).isEmpty();
        assertThat(validator.validate(new Contact(local64 + "a@clinica.test"))).hasSize(1);
        assertThat(validator.validate(new Contact("a@" + domain252))).isEmpty();
        assertThat(validator.validate(new Contact("ab@" + domain252))).hasSize(1);
        assertThat(validator.validate(new Contact("a@" + "b".repeat(64) + ".es")))
                .hasSize(1);
    }
}
