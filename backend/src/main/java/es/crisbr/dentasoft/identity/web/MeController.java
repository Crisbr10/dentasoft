package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.internal.MyClinics;
import es.crisbr.dentasoft.identity.internal.MyClinics.MyClinic;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user as Dentasoft sees them: their clinics and their role in each (M02-T03). Nothing personal: the
 * frontend reads the name and email from the ID token. Any signed-in user may call it, also without clinics.
 */
@RestController
class MeController {

    static final String PATH = "/api/v1/me";

    private final MyClinics myClinics;

    MeController(MyClinics myClinics) {
        this.myClinics = myClinics;
    }

    /** @param clinics the user's active memberships, sorted by clinic name; empty when they belong to none */
    record MeResponse(List<MyClinic> clinics) {}

    @GetMapping(PATH)
    MeResponse me() {
        return new MeResponse(myClinics.ofCurrentUser());
    }
}
