package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.internal.MyClinics;
import es.crisbr.dentasoft.identity.internal.MyClinics.MyClinic;
import es.crisbr.dentasoft.identity.internal.MyClinics.MyInvitation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user as Dentasoft sees them: their clinics and their role in each (M02-T03), and the invitations they
 * have not accepted yet (M02-T05). Nothing personal: the
 * frontend reads the name and email from the ID token. Any signed-in user may call it, also without clinics.
 */
@RestController
class MeController {

    static final String PATH = "/api/v1/me";

    private final MyClinics myClinics;

    MeController(MyClinics myClinics) {
        this.myClinics = myClinics;
    }

    /**
     * @param clinics the user's active memberships, sorted by clinic name; empty when they belong to none
     * @param invitations the invitations they have not accepted yet, sorted by clinic name
     */
    record MeResponse(List<MyClinic> clinics, List<MyInvitation> invitations) {}

    @GetMapping(PATH)
    MeResponse me() {
        return new MeResponse(myClinics.ofCurrentUser(), myClinics.invitationsOfCurrentUser());
    }

    /**
     * Accepts an invitation: the clinic is the user's from the next request. 404 unless it is one of their pending
     * invitations.
     */
    @PostMapping(PATH + "/invitations/{id}/acceptance")
    ResponseEntity<Void> accept(@PathVariable UUID id) {
        myClinics.accept(id);
        return ResponseEntity.noContent().build();
    }
}
