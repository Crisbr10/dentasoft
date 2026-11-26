package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestPostgresConfiguration;
import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.ClinicDirectory;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The check of the active clinic reads on the application's connection, never the platform's, so PostgreSQL enforces
 * it too: with the user's {@code app.user_id}, Row Level Security ({@code own_memberships}, {@code member_select})
 * hides someone else's clinic and memberships whatever the SQL asks for.
 */
@IntegrationTest
class ActiveClinicAccessTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    /** Member of A only. */
    private static final UUID USER_A = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004c1");
    /** Member of B only. */
    private static final UUID USER_B = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004c2");

    @Autowired
    private ActiveClinicAccess access;

    @Autowired
    private UserAccounts accounts;

    @Autowired
    private ClinicDirectory directory;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        IdentityTestDatabase.insertUser(USER_A);
        IdentityTestDatabase.insertUser(USER_B);
        IdentityTestDatabase.insertMembership(CLINIC_A, USER_A, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, USER_B, ClinicRole.ADMIN_CLINICA, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(List.of(USER_A, USER_B));
    }

    @Test
    void findsTheRoleOfTheUsersOwnMembershipOnly() {
        TestSecurityContext.runAs(USER_A, List.of(), () -> {
            assertThat(access.roleIn(CLINIC_A)).contains(ClinicRole.ODONTOLOGO);
            assertThat(access.roleIn(CLINIC_B)).isEmpty();
        });
    }

    /** Both reads go through the application's role, as the signed-in user. */
    @Test
    void readsOnTheApplicationsConnectionAsTheUser() {
        for (Object reader : List.of(accounts, directory)) {
            JdbcClient jdbc = jdbcOf(reader);
            TestSecurityContext.runAs(USER_A, List.of(), () -> {
                Map<String, Object> session = transaction.execute(status -> jdbc.sql(
                                "SELECT current_user AS role, current_setting('app.user_id', true) AS user_id")
                        .query()
                        .singleRow());
                assertThat(session)
                        .isEqualTo(Map.of("role", TestPostgresConfiguration.APP_USER, "user_id", USER_A.toString()));
            });
        }
    }

    /**
     * Even a query that forgot to ask for the user's own rows finds nothing of clinic B on those connections: the
     * database, not only the SQL, keeps a user out of someone else's clinic.
     */
    @Test
    void theDatabaseHidesSomeoneElsesClinicWhateverTheQuery() {
        for (Object reader : List.of(accounts, directory)) {
            JdbcClient jdbc = jdbcOf(reader);
            TestSecurityContext.runAs(
                    USER_A,
                    List.of(),
                    () -> transaction.executeWithoutResult(status -> {
                        assertThat(count(jdbc, "SELECT count(*) FROM clinic WHERE id = :clinic", CLINIC_B))
                                .isZero();
                        assertThat(count(
                                        jdbc,
                                        "SELECT count(*) FROM clinic_membership WHERE tenant_id = :clinic",
                                        CLINIC_B))
                                .isZero();
                        assertThat(count(jdbc, "SELECT count(*) FROM clinic WHERE id = :clinic", CLINIC_A))
                                .isOne();
                    }));
        }
    }

    private static JdbcClient jdbcOf(Object bean) {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        return (JdbcClient) ReflectionTestUtils.getField(target, "jdbc");
    }

    private static long count(JdbcClient jdbc, String sql, UUID clinic) {
        return jdbc.sql(sql).param("clinic", clinic).query(Long.class).single();
    }
}
