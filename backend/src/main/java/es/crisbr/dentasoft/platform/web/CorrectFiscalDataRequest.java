package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.ClinicAdministration.FiscalDataCorrection;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.shared.SpanishTaxId;
import es.crisbr.dentasoft.shared.TaxIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * A correction of a clinic's fiscal data (M01-T11).
 *
 * @param taxId the clinic's tax identifier (CIF, NIF or NIE), normalized before it is validated: {@code b-12345674} is
 *     {@code B12345674}
 * @param version the clinic's version the correction was made on, as the platform API returned it
 */
public record CorrectFiscalDataRequest(
        @NotBlank @NoControlCharacters @Size(max = 200) String legalName,
        @NotBlank @SpanishTaxId String taxId,
        @NotNull @PositiveOrZero Long version) {

    public CorrectFiscalDataRequest {
        taxId = TaxIds.normalize(taxId);
    }

    FiscalDataCorrection toCorrection() {
        return new FiscalDataCorrection(legalName.strip(), Objects.requireNonNull(taxId), version);
    }
}
