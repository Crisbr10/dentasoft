package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * A new treatment room.
 *
 * @param name unique, ignoring case, among the clinic's active rooms
 * @param color colour in the calendar, as {@code #RRGGBB} in upper case; optional
 */
public record LocationRequest(
        @NotBlank @NoControlCharacters @Size(max = 100) String name,
        @Nullable @Pattern(regexp = "#[0-9A-F]{6}") String color) {

    LocationData toData() {
        return new LocationData(name.strip(), color);
    }
}
