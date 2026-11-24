package es.crisbr.dentasoft.shared;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Text without control characters (U+0000 to U+001F and U+007F to U+009F), such as a name typed by a person: they
 * never belong in it, and PostgreSQL refuses U+0000 in text, which would otherwise end in a 500. Line breaks and tabs
 * are control characters too. {@code null} and an empty value are valid: that is for {@code @NotBlank}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoControlCharacters.Validator.class)
public @interface NoControlCharacters {

    String message() default "{es.crisbr.dentasoft.shared.NoControlCharacters.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NoControlCharacters, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.chars().noneMatch(Character::isISOControl);
        }
    }
}
