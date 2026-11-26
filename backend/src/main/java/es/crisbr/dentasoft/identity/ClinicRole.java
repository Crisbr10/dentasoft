package es.crisbr.dentasoft.identity;

/**
 * What a user is in one clinic: exactly one role per clinic, stored in {@code clinic_membership.role} (V007), never
 * taken from the token or from Keycloak's organizations (ADR 0015). The same roles, in the same order, as the columns
 * of {@code docs/plan/configuracion.md}; {@code PLATFORM_ADMIN} is not one of them, it is a realm role of the token.
 */
public enum ClinicRole {
    ADMIN_CLINICA,
    ODONTOLOGO,
    HIGIENISTA,
    RECEPCION,
    FACTURACION
}
