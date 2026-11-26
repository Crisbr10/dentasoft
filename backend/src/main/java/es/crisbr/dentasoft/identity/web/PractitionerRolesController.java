package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.internal.PractitionerRoles;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerDetails;
import es.crisbr.dentasoft.identity.persistence.PractitionerRoleStatus;
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
 * The active clinic's practitioner records (M02-T06): every member reads them, {@code ADMIN_CLINICA} manages them.
 * Records are never deleted: {@code POST …/deactivation} takes a practitioner out of new appointments and {@code POST
 * …/activation} puts them back. Authorization lives in {@link PractitionerRoles}.
 */
@RestController
@RequestMapping(PractitionerRolesController.PATH)
class PractitionerRolesController {

    static final String PATH = "/api/v1/clinic/practitioner-roles";

    private final PractitionerRoles practitioners;

    PractitionerRolesController(PractitionerRoles practitioners) {
        this.practitioners = practitioners;
    }

    /** @param status only the records with this status; all of them if missing */
    @GetMapping
    PagedModel<PractitionerDetails> list(
            @RequestParam(required = false) @Nullable PractitionerRoleStatus status,
            @PageableDefault(sort = "displayName", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(practitioners.list(status, pageable));
    }

    @GetMapping("/{id}")
    PractitionerDetails get(@PathVariable UUID id) {
        return practitioners.get(id);
    }

    @PostMapping
    ResponseEntity<PractitionerDetails> create(
            @Valid @RequestBody PractitionerRoleRequest body, HttpServletRequest request) {
        PractitionerDetails practitioner = practitioners.create(body.toData());
        // Relative, with the context path: never built from the request's Host header.
        URI uri = URI.create(request.getContextPath() + PATH + "/" + practitioner.id());
        return ResponseEntity.created(uri).body(practitioner);
    }

    @PutMapping("/{id}")
    PractitionerDetails update(@PathVariable UUID id, @Valid @RequestBody UpdatePractitionerRoleRequest body) {
        return practitioners.update(id, body.toData(), body.version());
    }

    @PostMapping("/{id}/deactivation")
    PractitionerDetails deactivate(
            @PathVariable UUID id, @Valid @RequestBody PractitionerRoleStatusChangeRequest body) {
        return practitioners.deactivate(id, body.version());
    }

    @PostMapping("/{id}/activation")
    PractitionerDetails activate(@PathVariable UUID id, @Valid @RequestBody PractitionerRoleStatusChangeRequest body) {
        return practitioners.activate(id, body.version());
    }
}
