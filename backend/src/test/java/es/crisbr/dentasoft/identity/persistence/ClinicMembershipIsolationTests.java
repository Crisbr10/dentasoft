package es.crisbr.dentasoft.identity.persistence;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantIsolationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A clinic never reads nor changes another clinic's memberships (V007, M02-T03). Each clinic's membership belongs to
 * its own invented user, whose account exists for the whole class: memberships point at it, and the template deletes
 * the memberships after each test.
 */
class ClinicMembershipIsolationTests extends TenantIsolationTest<ClinicMembership> {

    private static final List<UUID> USERS = List.of(
            UUID.fromString("5f0c2b1e-0000-4000-8000-0000000001a1"),
            UUID.fromString("5f0c2b1e-0000-4000-8000-0000000001b2"));

    @Autowired
    private ClinicMembershipRepository memberships;

    @BeforeAll
    static void createUsers() throws SQLException {
        for (UUID user : USERS) {
            IdentityTestDatabase.insertUser(user);
        }
    }

    @AfterAll
    static void deleteUsers() throws SQLException {
        IdentityTestDatabase.deleteUsers(USERS);
    }

    @Override
    protected JpaRepository<ClinicMembership, UUID> repository() {
        return memberships;
    }

    @Override
    protected String table() {
        return "clinic_membership";
    }

    @Override
    protected ClinicMembership newEntity(int n) {
        return new ClinicMembership(USERS.get(n), ClinicRole.ODONTOLOGO);
    }

    @Override
    protected void modify(ClinicMembership membership) {
        membership.setRole(ClinicRole.ADMIN_CLINICA);
    }
}
