package es.crisbr.dentasoft.identity;

/**
 * What a user may do in a section of the configuration area ({@link ConfigurationSection}): the «E» and «V» of the
 * matrix in {@code docs/plan/configuracion.md}. A section the user may not see has no access at all and is not listed.
 */
public enum SectionAccess {
    /** Sees and edits the section. */
    EDIT,
    /** Only sees it. */
    VIEW
}
