package es.crisbr.dentasoft.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * The ids of the clinics a process without a user works on, for {@link ClinicBatch}: only active clinics, never
 * suspended ones.
 *
 * <p>The application role only sees the active clinic (Row Level Security on {@code clinic.id}), so it cannot list
 * them. The platform module, which sees every clinic as {@code dentasoft_platform} (ADR 0011), implements it: the
 * interface is here because {@code platform} already depends on {@code tenancy}, and the opposite dependency would be a
 * cycle.
 */
public interface ActiveClinicIds {

    /** The ids of the active clinics, in id order. Fails with an active clinic: it works across clinics. */
    List<UUID> activeClinicIds();
}
