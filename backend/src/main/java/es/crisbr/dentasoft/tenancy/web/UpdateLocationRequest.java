package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The new name and colour of a treatment room.
 *
 * @param name unique, ignoring case, among the clinic's active rooms
 * @param color colour in the calendar, as {@code #RRGGBB} in upper case; missing clears it
 * @param version the version of the room the client read; the change is refused with 409 if it is no longer current
 */
public record UpdateLocationRequest(
        @NotBlank @NoControlCharacters @Size(max = 100) String name,
        @Nullable @Pattern(regexp = "#[0-9A-F]{6}") String color,
        @NotNull @PositiveOrZero Long version) {

    LocationData toData() {
        return new LocationData(name.strip(), color);
    }
}
