package es.crisbr.dentasoft.identity.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Deactivating a user's membership of the active clinic.
 *
 * @param version the version of the membership the client read; the change is refused with 409 if it is active and
 *     no longer at that version
 */
public record DeactivationRequest(@NotNull @PositiveOrZero Long version) {}
