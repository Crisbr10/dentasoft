package es.crisbr.dentasoft.tenancy;

/**
 * Clinic data was read or written without an active clinic: the code did not run inside {@link TenantContext#runAs}.
 * It is a programming error, never something the user can fix.
 */
public class NoActiveClinicException extends IllegalStateException {

    public NoActiveClinicException() {
        super("No active clinic: run this code inside TenantContext.runAs");
    }
}
