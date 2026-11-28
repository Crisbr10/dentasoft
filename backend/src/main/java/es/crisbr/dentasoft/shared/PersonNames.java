package es.crisbr.dentasoft.shared;

/**
 * The rule for a person's first or last name typed into Dentasoft, for {@code @Pattern}: the invitations of
 * {@code identity} and of {@code platform} and the practitioner records use the same one.
 */
public final class PersonNames {

    /**
     * At least one letter, and no format characters (U+200B zero-width space, U+202E right-to-left override…), which
     * {@code @NoControlCharacters} lets through: a name of only Unicode spaces would be blank once stripped, and format
     * characters can disguise what a name looks like. Except the zero-width non-joiner and joiner (U+200C, U+200D)
     * between two letters or marks, where Persian and Indic scripts need them in real names.
     */
    public static final String PATTERN =
            "(?=.*\\p{L})(?:[^\\p{Cf}]|(?<=[\\p{L}\\p{M}])[\\u200C\\u200D](?=[\\p{L}\\p{M}]))*";

    public static final String MESSAGE = "{es.crisbr.dentasoft.identity.PersonName.message}";

    private PersonNames() {}
}
