package es.crisbr.dentasoft.shared;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Tax identifiers as they are validated ({@link SpanishTaxId}) and stored. */
public final class TaxIds {

    private TaxIds() {}

    /**
     * {@code value} in upper case, without whitespace or dashes ({@code b-1234 5674} becomes {@code B12345674}), so the
     * same identifier is never stored in two ways. Anything else is left as it is, for the validator to refuse.
     */
    public static @Nullable String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints()
                .filter(c -> c != '-' && !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .forEach(normalized::appendCodePoint);
        return normalized.toString().toUpperCase(Locale.ROOT);
    }
}
