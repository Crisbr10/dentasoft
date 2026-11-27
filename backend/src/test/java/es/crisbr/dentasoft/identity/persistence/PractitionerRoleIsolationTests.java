package es.crisbr.dentasoft.identity.persistence;

import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.tenancy.TenantIsolationTest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A clinic never reads nor changes another clinic's practitioner records (V012, M02-T06). The records have no user,
 * as an external collaborator's, so they need no membership.
 */
class PractitionerRoleIsolationTests extends TenantIsolationTest<PractitionerRole> {

    @Autowired
    private PractitionerRoleRepository practitioners;

    @Override
    protected JpaRepository<PractitionerRole, UUID> repository() {
        return practitioners;
    }

    @Override
    protected String table() {
        return "practitioner_role";
    }

    @Override
    protected PractitionerRole newEntity(int n) {
        PractitionerRole practitioner =
                new PractitionerRole(null, "Dra. Pruebas " + (n + 1), PractitionerSpecialty.ORTHODONTICS, "#1E88E5");
        practitioner.setLicense("28", "1000" + n);
        return practitioner;
    }

    @Override
    protected void modify(PractitionerRole practitioner) {
        practitioner.setDisplayName(practitioner.getDisplayName() + " (cambiado)");
    }
}
