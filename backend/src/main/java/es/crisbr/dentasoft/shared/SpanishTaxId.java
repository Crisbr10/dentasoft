package es.crisbr.dentasoft.shared;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A Spanish tax identifier with a valid format and check character, already normalized with {@link
 * TaxIds#normalize(String)}: a CIF ({@code B12345674}), the NIF of a person, which is a DNI ({@code 12345678Z}), or a NIE ({@code
 * X1234567L}). The special NIFs that start with K, L or M are not accepted. {@code null} is valid: that is for
 * {@code @NotBlank}.
 *
 * <p>The validator does not normalize: a request normalizes its value first, in its constructor, so the value that is
 * validated is the one that is stored.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SpanishTaxId.Validator.class)
public @interface SpanishTaxId {

    String message() default "{es.crisbr.dentasoft.shared.SpanishTaxId.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SpanishTaxId, String> {

        private static final Pattern DNI = Pattern.compile("[0-9]{8}[A-Z]");
        private static final Pattern NIE = Pattern.compile("[XYZ][0-9]{7}[A-Z]");
        private static final Pattern CIF = Pattern.compile("[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-J]");

        /** The check letter of a DNI or NIE, by the number modulo 23. */
        private static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

        /** The check letter of a CIF, by its check digit. */
        private static final String CIF_LETTERS = "JABCDEFGHI";

        /** CIF types whose check character is always a digit, and those whose check character is always a letter. */
        private static final String CIF_DIGIT_TYPES = "ABEH";

        private static final String CIF_LETTER_TYPES = "NPQRSW";

        @Override
        public boolean isValid(@Nullable String value, @Nullable ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (DNI.matcher(value).matches()) {
                return dniLetter(Integer.parseInt(value.substring(0, 8))) == value.charAt(8);
            }
            if (NIE.matcher(value).matches()) {
                int prefix = "XYZ".indexOf(value.charAt(0));
                return dniLetter(Integer.parseInt(prefix + value.substring(1, 8))) == value.charAt(8);
            }
            if (CIF.matcher(value).matches()) {
                return validCif(value);
            }
            return false;
        }

        private static char dniLetter(int number) {
            return DNI_LETTERS.charAt(number % 23);
        }

        private static boolean validCif(String value) {
            int sum = 0;
            for (int i = 1; i <= 7; i++) {
                int digit = value.charAt(i) - '0';
                if (i % 2 == 1) {
                    // Odd positions are doubled, and the two digits of the result added.
                    int doubled = digit * 2;
                    sum += doubled / 10 + doubled % 10;
                } else {
                    sum += digit;
                }
            }
            int checkDigit = (10 - sum % 10) % 10;
            char type = value.charAt(0);
            char check = value.charAt(8);
            boolean digitMatches = check == (char) ('0' + checkDigit);
            boolean letterMatches = check == CIF_LETTERS.charAt(checkDigit);
            if (CIF_DIGIT_TYPES.indexOf(type) >= 0) {
                return digitMatches;
            }
            if (CIF_LETTER_TYPES.indexOf(type) >= 0) {
                return letterMatches;
            }
            return digitMatches || letterMatches;
        }
    }
}
