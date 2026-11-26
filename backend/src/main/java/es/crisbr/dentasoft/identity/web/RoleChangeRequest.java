package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.ClinicRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A user's new role in the active clinic.
 *
 * @param version the version of the membership the client read; the change is refused with 409 if it is no longer
 *     current
 */
public record RoleChangeRequest(
        @NotNull ClinicRole role, @NotNull @PositiveOrZero Long version) {}
