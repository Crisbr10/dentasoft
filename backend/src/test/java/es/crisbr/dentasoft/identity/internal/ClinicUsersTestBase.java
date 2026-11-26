package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Clinic A with one administrator, {@link #ADMIN}, and the fake Keycloak emptied before each test. Every user the
 * tests create, in Keycloak or in the database, is deleted afterwards, and so are the invitation quotas in Redis.
 */
abstract class ClinicUsersTestBase {

    static final UUID CLINIC_A = TestData.CLINIC_A.id();
    static final UUID CLINIC_B = TestData.CLINIC_B.id();
    static final UUID ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000005a1");

    @Autowired
    ClinicUsers users;

    @Autowired
    FakeKeycloak keycloak;

    @Autowired
    private StringRedisTemplate redis;

    /** Users of the database to delete after the test, besides those the fake Keycloak knows. */
    final List<UUID> createdUsers = new ArrayList<>();

    @BeforeEach
    void seedClinicWithAnAdministrator() throws SQLException {
        keycloak.reset();
        deleteQuotas();
        TenancyTestDatabase.insertClinics();
        IdentityTestDatabase.insertUser(ADMIN);
        IdentityTestDatabase.insertMembership(CLINIC_A, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        createdUsers.add(ADMIN);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        List<UUID> all = new ArrayList<>(createdUsers);
        keycloak.users().forEach(user -> all.add(user.id()));
        IdentityTestDatabase.deleteUsers(all);
        deleteQuotas();
    }

    private void deleteQuotas() {
        Set<String> keys = redis.keys("dentasoft:quota:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** A user with their account set up in Keycloak, and a membership of {@code clinic}. */
    UUID member(UUID clinic, ClinicRole role) throws SQLException {
        UUID user = keycloak.addUser(
                "miembro-" + UUID.randomUUID() + "@clinica.test", "Miembro", "Pruebas", true, List.of());
        IdentityTestDatabase.insertUser(user);
        IdentityTestDatabase.insertMembership(clinic, user, role, true);
        createdUsers.add(user);
        return user;
    }

    static ClinicUsers.Invitation invitation(String email, ClinicRole role) {
        return new ClinicUsers.Invitation(email, "Ana", "Pruebas Inventada", role);
    }

    /** Runs {@code operation} as {@link #ADMIN} in clinic A, as {@code ActiveClinicFilter} leaves a request. */
    static <T> T asAdmin(Supplier<T> operation) {
        return as(ADMIN, List.of("ADMIN_CLINICA"), operation);
    }

    static void asAdmin(Runnable operation) {
        asAdmin(() -> {
            operation.run();
            return null;
        });
    }

    static <T> T as(UUID user, List<String> roles, Supplier<T> operation) {
        return as(user, roles, CLINIC_A, operation);
    }

    static <T> T as(UUID user, List<String> roles, UUID clinic, Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        TestSecurityContext.runAs(user, roles, () -> TenantContext.runAs(clinic, () -> result.set(operation.get())));
        return result.get();
    }

    /** The id of {@code user}'s membership of clinic A, as the administrator sees it in the list. */
    UUID membershipOf(UUID user) {
        return asAdmin(() -> users.list(org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .filter(details -> details.userId().equals(user))
                .findFirst()
                .orElseThrow()
                .id());
    }

    ClinicUserDetails detailsOf(UUID user) {
        return asAdmin(() -> users.list(org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .filter(details -> details.userId().equals(user))
                .findFirst()
                .orElseThrow());
    }
}
