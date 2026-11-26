package es.crisbr.dentasoft.identity;

import java.util.UUID;

/**
 * A practitioner of the active clinic as other modules show it: in the calendar (M06), quotes and documents.
 *
 * @param id the {@code practitioner_role} id, which appointments point at
 * @param displayName how the clinic names the practitioner
 * @param agendaColor colour in the calendar, as {@code #RRGGBB}
 * @param active whether new appointments may be given with this practitioner
 */
public record PractitionerSummary(
        UUID id, String displayName, PractitionerSpecialty specialty, String agendaColor, boolean active) {}
