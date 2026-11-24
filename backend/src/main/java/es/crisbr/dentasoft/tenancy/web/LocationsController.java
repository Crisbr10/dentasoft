package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.tenancy.internal.Locations;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationDetails;
import es.crisbr.dentasoft.tenancy.persistence.LocationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The active clinic's treatment rooms (gabinetes), for {@code ADMIN_CLINICA} (M01-T06). Rooms are never deleted:
 * {@code POST …/deactivation} takes one out of use and {@code POST …/activation} puts it back. Closed to every request
 * until M02-T02 (see {@code TenancySecurityConfiguration}); authorization lives in {@link Locations}.
 */
@RestController
@RequestMapping(LocationsController.PATH)
class LocationsController {

    static final String PATH = "/api/v1/clinic/locations";

    private final Locations locations;

    LocationsController(Locations locations) {
        this.locations = locations;
    }

    /** @param status only the rooms with this status; all of them if missing */
    @GetMapping
    PagedModel<LocationDetails> list(
            @RequestParam(required = false) @Nullable LocationStatus status,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(locations.list(status, pageable));
    }

    @GetMapping("/{id}")
    LocationDetails get(@PathVariable UUID id) {
        return locations.get(id);
    }

    @PostMapping
    ResponseEntity<LocationDetails> create(@Valid @RequestBody LocationRequest body, HttpServletRequest request) {
        LocationDetails location = locations.create(body.toData());
        // Relative, with the context path: never built from the request's Host header.
        URI uri = URI.create(request.getContextPath() + PATH + "/" + location.id());
        return ResponseEntity.created(uri).body(location);
    }

    @PutMapping("/{id}")
    LocationDetails update(@PathVariable UUID id, @Valid @RequestBody UpdateLocationRequest body) {
        return locations.update(id, body.toData(), body.version());
    }

    @PostMapping("/{id}/deactivation")
    LocationDetails deactivate(@PathVariable UUID id, @Valid @RequestBody LocationStatusChangeRequest body) {
        return locations.deactivate(id, body.version());
    }

    @PostMapping("/{id}/activation")
    LocationDetails activate(@PathVariable UUID id, @Valid @RequestBody LocationStatusChangeRequest body) {
        return locations.activate(id, body.version());
    }
}
