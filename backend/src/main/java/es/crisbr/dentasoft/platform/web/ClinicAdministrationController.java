package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.ClinicAdministration;
import es.crisbr.dentasoft.platform.internal.ClinicAdministration.ClinicDetails;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clinic administration API for {@code PLATFORM_ADMIN} (M01-T05). Closed to every request until M02-T02 (see {@code
 * PlatformSecurityConfiguration}); authorization lives in {@link ClinicAdministration}.
 */
@RestController
@RequestMapping(ClinicAdministrationController.PATH)
class ClinicAdministrationController {

    static final String PATH = "/api/v1/platform/clinics";

    private final ClinicAdministration administration;

    ClinicAdministrationController(ClinicAdministration administration) {
        this.administration = administration;
    }

    @PostMapping
    ResponseEntity<ClinicDetails> create(@Valid @RequestBody CreateClinicRequest request) {
        ClinicDetails clinic = administration.create(request.toNewClinic());
        // Relative: never built from the request's Host header.
        return ResponseEntity.created(URI.create(PATH + "/" + clinic.id())).body(clinic);
    }

    @GetMapping
    PagedModel<ClinicDetails> list(@PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(administration.list(pageable));
    }

    @PostMapping("/{id}/suspension")
    ClinicDetails suspend(@PathVariable UUID id) {
        return administration.suspend(id);
    }

    @PostMapping("/{id}/reactivation")
    ClinicDetails reactivate(@PathVariable UUID id) {
        return administration.reactivate(id);
    }
}
