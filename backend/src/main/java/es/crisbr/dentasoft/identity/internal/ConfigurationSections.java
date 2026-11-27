package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.ConfigurationSection;
import es.crisbr.dentasoft.identity.ConfigurationSection.VisibleSection;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The configuration sections the signed-in user sees (M02-T09): by their role in the active clinic, or as {@code
 * PLATFORM_ADMIN}. Only what the menu shows; each section's endpoints check the role themselves.
 */
@Service
public class ConfigurationSections {

    /** Any member of the active clinic: the roles exist only there (ADR 0015). */
    private static final String MEMBER =
            "hasAnyRole('ADMIN_CLINICA', 'ODONTOLOGO', 'HIGIENISTA', 'RECEPCION', 'FACTURACION')";

    /** The sections of the user's role in the active clinic, set by {@link ActiveClinicFilter} for this request. */
    @PreAuthorize(MEMBER)
    public List<VisibleSection> ofActiveClinic() {
        return ConfigurationSection.visibleTo(activeClinicRole());
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<VisibleSection> ofPlatformAdmin() {
        return ConfigurationSection.visibleToPlatformAdmin();
    }

    private static ClinicRole activeClinicRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new AccessDeniedException("No signed-in user");
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        // ActiveClinicFilter adds exactly one clinic role per request.
        return Arrays.stream(ClinicRole.values())
                .filter(role -> authorities.contains(ActiveClinicFilter.authority(role)))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("No role in the active clinic"));
    }
}
