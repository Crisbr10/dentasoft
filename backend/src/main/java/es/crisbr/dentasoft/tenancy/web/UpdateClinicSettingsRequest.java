package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsChanges;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The clinic's settings. Contact email and phone are optional: empty or missing clears them.
 *
 * @param defaultAppointmentMinutes default length of a new appointment, from 5 to 480 minutes, as the column allows
 * @param contactPhone digits and spaces, with an optional leading {@code +}
 * @param version the version of the settings the client read; the change is refused with 409 if it is no longer
 *     current
 */
public record UpdateClinicSettingsRequest(
        @NotNull @Min(5) @Max(480) Integer defaultAppointmentMinutes,
        @Nullable @Email @Size(max = 254) String contactEmail,
        @Nullable @Pattern(regexp = "\\+?[0-9 ]{0,31}") String contactPhone,
        @NotNull @PositiveOrZero Long version) {

    SettingsChanges toChanges() {
        return new SettingsChanges(
                defaultAppointmentMinutes, blankToNull(contactEmail), blankToNull(contactPhone), version);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
