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

/**
 * An email address a message can really be sent to, stricter than {@code @Email}, which accepts {@code ana@localhost}
 * or quoted local parts: {@code local@domain} where
 *
 * <ul>
 *   <li>the whole address has at most 254 characters, and the local part at most 64;
 *   <li>the local part is a dot-atom: letters, digits and {@code !#$%&'*+/=?^_`{|}~-}, with dots only between them;
 *   <li>the domain has at least two labels of letters, digits and inner hyphens, each of 1 to 63 characters, and ends
 *       in a top-level domain of at least two letters (or an {@code xn--} one);
 *   <li>there are no IP literals, no comments, no spaces and no characters outside ASCII (an international domain is
 *       written in its {@code xn--} form).
 * </ul>
 *
 * {@code null} is valid: that is for {@code @NotNull}/{@code @NotBlank}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrictEmail.Validator.class)
public @interface StrictEmail {

    String message() default "{es.crisbr.dentasoft.shared.StrictEmail.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrictEmail, String> {

        static final int MAX_LENGTH = 254;
        static final int MAX_LOCAL_LENGTH = 64;

        private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
        private static final Pattern LOCAL = Pattern.compile(ATOM + "(?:\\." + ATOM + ")*");
        private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
        private static final Pattern DOMAIN =
                Pattern.compile("(?:" + LABEL + "\\.)+(?:[A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.length() > MAX_LENGTH) {
                return false;
            }
            int at = value.lastIndexOf('@');
            if (at <= 0 || at > MAX_LOCAL_LENGTH || at == value.length() - 1) {
                return false;
            }
            return LOCAL.matcher(value.substring(0, at)).matches()
                    && DOMAIN.matcher(value.substring(at + 1)).matches();
        }
    }
}
