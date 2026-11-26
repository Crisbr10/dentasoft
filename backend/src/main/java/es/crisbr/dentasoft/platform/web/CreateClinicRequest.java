package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.ClinicAdministration.NewClinic;
import es.crisbr.dentasoft.shared.KnownTimeZone;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.shared.SpanishTaxId;
import es.crisbr.dentasoft.shared.TaxIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A new clinic. Time zone and locale are optional: without them, {@code Europe/Madrid} and {@code es-ES}.
 *
 * @param taxId the clinic's tax identifier (CIF, NIF or NIE), normalized before it is validated: {@code b-12345674} is
 *     {@code B12345674}
 */
public record CreateClinicRequest(
        @NotBlank @NoControlCharacters @Size(max = 200) String name,
        @NotBlank @NoControlCharacters @Size(max = 200) String legalName,
        @NotBlank @SpanishTaxId String taxId,
        @Nullable @KnownTimeZone String timezone,
        @Nullable @Pattern(regexp = "[a-z]{2}-[A-Z]{2}") String locale) {

    public CreateClinicRequest {
        taxId = TaxIds.normalize(taxId);
    }

    NewClinic toNewClinic() {
        return new NewClinic(name.strip(), legalName.strip(), Objects.requireNonNull(taxId), timezone, locale);
    }
}
