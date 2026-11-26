package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every transaction of a signed-in user passes them to PostgreSQL ({@code app.user_id}, V007), including the {@code
 * REQUIRES_NEW} ones of the same request, so Row Level Security never finds it empty by mistake; a job, without a
 * user, never has one, not even right after a request.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DatabaseUserTests {

    private static final UUID USER = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000005a1");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ClinicBatch batch;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of());
        IdentityTestDatabase.deleteUsers(List.of(USER));
    }

    @Test
    void aRequestPassesItsUserToEveryTransactionAlsoRequiresNew() {
        assertThat(mvc.get()
                        .uri(DatabaseUserTestEndpoint.PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(USER).bearer())
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("{\"required\": \"%s\", \"requiresNew\": \"%s\"}".formatted(USER, USER));
    }

    @Test
    void aJobHasNoUserInAnyTransaction() {
        aRequestPassesItsUserToEveryTransactionAlsoRequiresNew();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());

        ClinicBatch.Result result = batch.forEachActiveClinic("database-user-probe", clinicId -> {
            seen.add(DatabaseUserTestEndpoint.databaseUser(jdbc));
            seen.add(requiresNew.execute(inner -> DatabaseUserTestEndpoint.databaseUser(jdbc)));
        });

        assertThat(result.failed()).isEmpty();
        assertThat(result.succeeded()).contains(TestData.CLINIC_A.id(), TestData.CLINIC_B.id());
        assertThat(seen).hasSize(2 * result.succeeded().size()).containsOnly("");
    }
}
