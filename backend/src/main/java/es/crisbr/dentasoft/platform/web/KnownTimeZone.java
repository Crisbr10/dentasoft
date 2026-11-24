package es.crisbr.dentasoft.platform.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;
import java.util.Set;

/** A region time zone ID Java knows, such as {@code Europe/Madrid}. {@code null} is valid. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = KnownTimeZone.Validator.class)
public @interface KnownTimeZone {

    String message() default "{es.crisbr.dentasoft.platform.web.KnownTimeZone.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<KnownTimeZone, String> {

        /** {@link ZoneId#getAvailableZoneIds()} builds a new set on every call. */
        private static final Set<String> ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || ZONES.contains(value);
        }
    }
}
