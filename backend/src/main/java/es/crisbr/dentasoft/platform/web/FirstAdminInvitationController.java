package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.FirstAdminInvitations;
import es.crisbr.dentasoft.platform.internal.FirstAdminInvitations.AdminInvitationStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitation of a clinic's first administrator by the platform (M02-T11, ADR 0018): only while the clinic has no
 * administrator, accepted or pending. Once they accept, the clinic's own administrators invite everyone else.
 */
@RestController
@RequestMapping(FirstAdminInvitationController.PATH)
class FirstAdminInvitationController {

    static final String PATH = ClinicAdministrationController.PATH + "/{id}/admin-invitation";

    private final FirstAdminInvitations invitations;

    FirstAdminInvitationController(FirstAdminInvitations invitations) {
        this.invitations = invitations;
    }

    /** NONE, PENDING (with when it was sent) or ACCEPTED (with when): never the person's name or email. */
    @GetMapping
    AdminInvitationStatus status(@PathVariable UUID id) {
        return invitations.status(id);
    }

    /**
     * 202 with no body, whether the person already had a Dentasoft account or not, so the answer reveals nothing about
     * other clinics.
     */
    @PostMapping
    ResponseEntity<Void> invite(@PathVariable UUID id, @Valid @RequestBody AdminInvitationRequest body) {
        invitations.invite(id, body.toNewAdministrator());
        return ResponseEntity.accepted().build();
    }

    /** Sends the pending invitation's email again, to someone who has not set up their account yet. */
    @PostMapping("/resend")
    ResponseEntity<Void> resend(@PathVariable UUID id) {
        invitations.resend(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/cancellation")
    ResponseEntity<Void> cancel(@PathVariable UUID id) {
        invitations.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
