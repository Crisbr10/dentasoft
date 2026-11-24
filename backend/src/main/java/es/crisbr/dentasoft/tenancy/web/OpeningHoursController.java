package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The active clinic's opening hours, for {@code ADMIN_CLINICA} (M01-T06): read the week, or replace it whole. Closed to
 * every request until M02-T02 (see {@code TenancySecurityConfiguration}); authorization lives in {@link
 * ClinicOpeningHours}.
 */
@RestController
@RequestMapping(OpeningHoursController.PATH)
class OpeningHoursController {

    static final String PATH = "/api/v1/clinic/hours-of-operation";

    private final ClinicOpeningHours openingHours;

    OpeningHoursController(ClinicOpeningHours openingHours) {
        this.openingHours = openingHours;
    }

    @GetMapping
    List<Slot> week() {
        return openingHours.week();
    }

    @PutMapping
    List<Slot> replaceWeek(@Valid @RequestBody ReplaceOpeningHoursRequest body) {
        return openingHours.replaceWeek(body.toSlots());
    }
}
