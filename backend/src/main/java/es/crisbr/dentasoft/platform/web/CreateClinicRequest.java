package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.ClinicAdministration.NewClinic;
import es.crisbr.dentasoft.shared.KnownTimeZone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * A new clinic. Time zone and locale are optional: without them, {@code Europe/Madrid} and {@code es-ES}.
 *
 * @param taxId the clinic's tax identifier (CIF/NIF), in upper case without spaces or dashes
 */
public record CreateClinicRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String legalName,
        @NotBlank @Pattern(regexp = "[A-Z0-9]{1,20}") String taxId,
        @Nullable @KnownTimeZone String timezone,
        @Nullable @Pattern(regexp = "[a-z]{2}-[A-Z]{2}") String locale) {

    NewClinic toNewClinic() {
        return new NewClinic(name.strip(), legalName.strip(), taxId, timezone, locale);
    }
}
