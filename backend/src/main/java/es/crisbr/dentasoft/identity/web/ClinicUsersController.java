package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.internal.ClinicUserDetails;
import es.crisbr.dentasoft.identity.internal.ClinicUsers;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The active clinic's users, for {@code ADMIN_CLINICA} (M02-T05). Memberships are never deleted: {@code POST
 * …/deactivation} ends one, and inviting the person again makes it active. Authorization lives in {@link ClinicUsers}.
 */
@RestController
@RequestMapping(ClinicUsersController.PATH)
class ClinicUsersController {

    static final String PATH = "/api/v1/clinic/users";

    private final ClinicUsers users;

    ClinicUsersController(ClinicUsers users) {
        this.users = users;
    }

    /** Sortable by {@code role}, {@code active} and {@code createdAt}; names live in Keycloak. */
    @GetMapping
    PagedModel<ClinicUserDetails> list(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(users.list(pageable));
    }

    /**
     * 202 with no body in every case, whether the person already had a Dentasoft account (in another clinic) or not,
     * so the answer reveals nothing about other clinics. The new user appears in the list.
     */
    @PostMapping("/invitations")
    ResponseEntity<Void> invite(@Valid @RequestBody InvitationRequest body) {
        users.invite(body.toInvitation());
        return ResponseEntity.accepted().build();
    }

    /** Sends the invitation email again, to someone who has not set up their account yet. */
    @PostMapping("/{id}/invitation")
    ResponseEntity<Void> resendInvitation(@PathVariable UUID id) {
        users.resendInvitation(id);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{id}/role")
    ClinicUserDetails changeRole(@PathVariable UUID id, @Valid @RequestBody RoleChangeRequest body) {
        return users.changeRole(id, body.role(), body.version());
    }

    @PostMapping("/{id}/deactivation")
    ClinicUserDetails deactivate(@PathVariable UUID id, @Valid @RequestBody DeactivationRequest body) {
        return users.deactivate(id, body.version());
    }
}
