package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.ConfigurationSection.VisibleSection;
import es.crisbr.dentasoft.identity.internal.ConfigurationSections;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sections of the configuration area the user sees, in menu order (M02-T09). A member of a clinic asks with the
 * active clinic ({@code X-Clinic-Id}); {@code PLATFORM_ADMIN}, who has no clinic, asks on the platform API, whose chain
 * lets only that role through. Authorization lives in {@link ConfigurationSections}.
 */
@RestController
class ConfigurationSectionsController {

    static final String CLINIC_PATH = "/api/v1/clinic/configuration/sections";
    static final String PLATFORM_PATH = "/api/v1/platform/configuration/sections";

    private final ConfigurationSections sections;

    ConfigurationSectionsController(ConfigurationSections sections) {
        this.sections = sections;
    }

    @GetMapping(CLINIC_PATH)
    List<VisibleSection> clinicSections() {
        return sections.ofActiveClinic();
    }

    @GetMapping(PLATFORM_PATH)
    List<VisibleSection> platformSections() {
        return sections.ofPlatformAdmin();
    }
}
