package es.crisbr.dentasoft.identity.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Deactivating or activating a practitioner record.
 *
 * @param version the version of the record the client read; the change is refused with 409 if it is no longer current
 */
public record PractitionerRoleStatusChangeRequest(
        @NotNull @PositiveOrZero Long version) {}
