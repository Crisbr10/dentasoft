package es.crisbr.dentasoft.platform;

import java.util.UUID;

/**
 * A clinic was created, with its initial settings, by the platform administration. Published inside the creating
 * transaction once both rows are written, so {@code @ApplicationModuleListener}s receive it only if it commits.
 *
 * <p>A listener that creates data for the new clinic must make it the active clinic first (M01-T08).
 */
public record ClinicCreated(UUID clinicId) {}
