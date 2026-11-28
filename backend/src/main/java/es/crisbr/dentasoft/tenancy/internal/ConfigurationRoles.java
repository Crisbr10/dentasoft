package es.crisbr.dentasoft.tenancy.internal;

/**
 * Who reads and who edits the «Clínica» section of the configuration area (M01-T10), as {@code @PreAuthorize}
 * expressions: the {@code CLINIC} row of {@code ConfigurationSection}, which {@code ConfigurationRolesMatrixTests}
 * compares with these annotations.
 *
 * <p>Each service is {@link #ADMIN} as a whole, so a method without its own annotation is closed to everyone but the
 * clinic administrator; only its reads open to {@link #MEMBER}. The roles are those of {@code ClinicRole}: tenancy
 * cannot refer to identity, which depends on it.
 */
final class ConfigurationRoles {

    /** Any role of the active clinic: every one of them sees the section. */
    static final String MEMBER = "hasAnyRole('ADMIN_CLINICA', 'ODONTOLOGO', 'HIGIENISTA', 'RECEPCION', 'FACTURACION')";

    /** Only the clinic administrator edits it. */
    static final String ADMIN = "hasRole('ADMIN_CLINICA')";

    private ConfigurationRoles() {}
}
