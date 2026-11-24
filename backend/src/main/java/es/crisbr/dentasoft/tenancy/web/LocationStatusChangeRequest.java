package es.crisbr.dentasoft.tenancy.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Deactivating or activating a treatment room.
 *
 * @param version the version of the room the client read; the change is refused with 409 if it is no longer current
 */
public record LocationStatusChangeRequest(
        @NotNull @PositiveOrZero Long version) {}
