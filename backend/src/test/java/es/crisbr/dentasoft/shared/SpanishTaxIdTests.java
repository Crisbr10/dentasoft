package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Format and check character of a CIF, a NIF (DNI) or a NIE, and the normalization that comes before it. */
class SpanishTaxIdTests {

    private final SpanishTaxId.Validator validator = new SpanishTaxId.Validator();

    @ParameterizedTest
    @ValueSource(
            strings = {
                // CIF with a check digit: A, B, E and H always use one.
                "B12345674",
                "B00000000",
                "A58818501",
                "B87654323",
                // CIF with a check letter: N, P, Q, R, S and W always use one.
                "Q1234567D",
                "P0000000J",
                // CIF of a type that accepts either.
                "G12345674",
                "G1234567D",
                // NIF of a person (DNI) and NIE.
                "12345678Z",
                "00000000T",
                "X1234567L",
                "Y1234567X",
                "Z1234567R"
            })
    void acceptsValidTaxIds(String taxId) {
        assertThat(validator.isValid(taxId, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Wrong check character.
                "B12345678",
                "Q1234567E",
                "12345678A",
                "X1234567A",
                // A type that needs a digit, with a letter, and the other way round.
                "B1234567D",
                "Q12345674",
                // Not a CIF type letter.
                "I12345674",
                "O12345674",
                "T12345674",
                // Wrong format.
                "B1234567",
                "B123456740",
                "1234567Z",
                "B1234567-4",
                "b12345674",
                " B12345674",
                "XX1234567",
                ""
            })
    void rejectsInvalidTaxIds(String taxId) {
        assertThat(validator.isValid(taxId, null)).isFalse();
    }

    /** {@code null} is for {@code @NotBlank}. */
    @Test
    void acceptsNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "b-12345674|B12345674",
                "B12345674|B12345674",
                " b 1234 5674 |B12345674",
                "x-1234567-l|X1234567L",
                "12.345.678-z|12.345.678Z"
            })
    void normalizesToUpperCaseWithoutSpacesOrDashes(String raw, String normalized) {
        assertThat(TaxIds.normalize(raw)).isEqualTo(normalized);
    }

    @Test
    void normalizingNullGivesNull() {
        assertThat(TaxIds.normalize(null)).isNull();
    }

    /** Tabs and other whitespace count as spaces; the result is validated afterwards, so nothing else is removed. */
    @Test
    void removesAnyWhitespace() {
        assertThat(TaxIds.normalize("B1234\t5674 ")).isEqualTo("B12345674");
    }
}
