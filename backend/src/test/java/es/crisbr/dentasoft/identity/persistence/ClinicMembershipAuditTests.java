package es.crisbr.dentasoft.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.ClinicTransactions;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every membership change is in {@code audit_log} from the start (M02-T03), although the endpoints that make them come
 * with M02-T05: creating it, changing its role and deactivating it, each recorded as the user who did it.
 */
@IntegrationTest
class ClinicMembershipAuditTests {

    private static final UUID CLINIC = TestData.CLINIC_A.id();
    private static final UUID MEMBER = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000002a1");
    private static final UUID ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000002b2");

    @Autowired
    private ClinicMembershipRepository memberships;

    @Autowired
    private ClinicTransactions transactions;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        IdentityTestDatabase.insertUser(MEMBER);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(List.of(MEMBER));
    }

    @Test
    void creatingChangingTheRoleAndDeactivatingAreAudited() throws SQLException {
        AtomicReference<UUID> id = new AtomicReference<>();
        TestSecurityContext.runAs(ADMIN, List.of(), () -> {
            transactions.run(
                    CLINIC,
                    () -> id.set(memberships
                            .save(new ClinicMembership(MEMBER, ClinicRole.RECEPCION))
                            .getId()));
            transactions.run(
                    CLINIC, () -> memberships.findById(id.get()).orElseThrow().setRole(ClinicRole.ODONTOLOGO));
            transactions.run(
                    CLINIC, () -> memberships.findById(id.get()).orElseThrow().deactivate());
        });

        List<StoredEntry> entries = AuditLogDatabase.entries(CLINIC, id.get());
        assertThat(entries).extracting(StoredEntry::action).containsExactly("CREATE", "UPDATE", "UPDATE");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.entityType()).isEqualTo("CLINIC_MEMBERSHIP");
            assertThat(entry.actorType()).isEqualTo("USER");
            assertThat(entry.actorId()).isEqualTo(ADMIN);
            assertThat(entry.outcome()).isEqualTo("SUCCESS");
        });
    }
}
