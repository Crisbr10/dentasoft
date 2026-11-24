package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.shared.KnownTimeZone;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * What the clinic changes of itself: its name and time zone. Its legal name and tax id are not here: only the platform
 * corrects them (M01-T11).
 *
 * @param version the version of the clinic the client read; the change is refused with 409 if it is no longer current
 */
public record UpdateClinicRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @KnownTimeZone String timezone,
        @NotNull @PositiveOrZero Long version) {

    ClinicChanges toChanges() {
        return new ClinicChanges(name.strip(), timezone, version);
    }
}
