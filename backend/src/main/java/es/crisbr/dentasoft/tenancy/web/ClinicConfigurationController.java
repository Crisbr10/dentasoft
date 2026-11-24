package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicDetails;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsDetails;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The active clinic's data and settings, for {@code ADMIN_CLINICA} (M01-T06). There is no clinic id in the path: it is
 * always the clinic of {@code TenantContext}. Closed to every request until M02-T02 (see {@code
 * TenancySecurityConfiguration}); authorization lives in {@link ClinicConfiguration}.
 */
@RestController
@RequestMapping(ClinicConfigurationController.PATH)
class ClinicConfigurationController {

    static final String PATH = "/api/v1/clinic";

    private final ClinicConfiguration configuration;

    ClinicConfigurationController(ClinicConfiguration configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    ClinicDetails clinic() {
        return configuration.clinic();
    }

    @PutMapping
    ClinicDetails updateClinic(@Valid @RequestBody UpdateClinicRequest body) {
        return configuration.updateClinic(body.toChanges());
    }

    @GetMapping("/settings")
    SettingsDetails settings() {
        return configuration.settings();
    }

    @PutMapping("/settings")
    SettingsDetails updateSettings(@Valid @RequestBody UpdateClinicSettingsRequest body) {
        return configuration.updateSettings(body.toChanges());
    }
}
