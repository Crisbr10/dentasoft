package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.identity.SectionAccess.EDIT;
import static es.crisbr.dentasoft.identity.SectionAccess.VIEW;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The sections of the configuration area and who sees each one (M02-T09): the matrix of {@code
 * docs/plan/configuracion.md} («Secciones y permisos»), in its order, which is the order of the menu. Only the
 * sections that exist are listed; the task that builds a new section adds its row here and in {@code
 * ConfigurationSectionTests}.
 *
 * <p>This is what the frontend shows, not access control: every endpoint behind a section checks the role itself with
 * {@code @PreAuthorize}. Each section's data is audited by its own entities ({@code @AuditedEntity}).
 */
public enum ConfigurationSection {
    /** Clinic data, opening hours, time zone and treatment rooms (M01-T06, M01-T10). */
    CLINIC(Map.of(
            ClinicRole.ADMIN_CLINICA, EDIT,
            ClinicRole.ODONTOLOGO, VIEW,
            ClinicRole.HIGIENISTA, VIEW,
            ClinicRole.RECEPCION, VIEW,
            ClinicRole.FACTURACION, VIEW)),
    /** Inviting users, changing their role and deactivating them (M02-T05, M02-T08). */
    USERS_AND_ROLES(Map.of(ClinicRole.ADMIN_CLINICA, EDIT)),
    /** Practitioner records (M02-T06, M02-T08). */
    PRACTITIONERS(Map.of(
            ClinicRole.ADMIN_CLINICA, EDIT,
            ClinicRole.ODONTOLOGO, VIEW,
            ClinicRole.HIGIENISTA, VIEW,
            ClinicRole.RECEPCION, VIEW,
            ClinicRole.FACTURACION, VIEW)),
    /** Clinic administration of the platform (M01-T05, M02-T08, M02-T11): only {@code PLATFORM_ADMIN}. */
    PLATFORM(Map.of(), EDIT);

    /**
     * A section the user sees, and what they may do in it.
     *
     * @param section the section
     * @param access whether they edit it or only see it
     */
    public record VisibleSection(ConfigurationSection section, SectionAccess access) {}

    private final Map<ClinicRole, SectionAccess> clinicAccess;
    private final @Nullable SectionAccess platformAccess;

    ConfigurationSection(Map<ClinicRole, SectionAccess> clinicAccess) {
        this(clinicAccess, null);
    }

    ConfigurationSection(Map<ClinicRole, SectionAccess> clinicAccess, @Nullable SectionAccess platformAccess) {
        this.clinicAccess = clinicAccess.isEmpty() ? Map.of() : new EnumMap<>(clinicAccess);
        this.platformAccess = platformAccess;
    }

    /** The sections a member of a clinic with {@code role} sees there, in menu order. */
    public static List<VisibleSection> visibleTo(ClinicRole role) {
        return Arrays.stream(values())
                .flatMap(section -> Optional.ofNullable(section.clinicAccess.get(role)).stream()
                        .map(access -> new VisibleSection(section, access)))
                .toList();
    }

    /**
     * The sections of {@code PLATFORM_ADMIN}, a realm role with no clinic. A platform administrator never sees a
     * clinic's sections: to work in one they use another account (ADR 0016).
     */
    public static List<VisibleSection> visibleToPlatformAdmin() {
        return Arrays.stream(values())
                .flatMap(section -> Optional.ofNullable(section.platformAccess).stream()
                        .map(access -> new VisibleSection(section, access)))
                .toList();
    }
}
