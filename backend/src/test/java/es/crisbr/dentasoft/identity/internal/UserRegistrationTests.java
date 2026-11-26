package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The account of a user is created on their first access, once, even when their first requests arrive together. */
@IntegrationTest
class UserRegistrationTests {

    private static final int SIMULTANEOUS_REQUESTS = 8;

    private final UUID user = UUID.randomUUID();

    @Autowired
    private UserAccounts accounts;

    @AfterEach
    void deleteUser() throws SQLException {
        IdentityTestDatabase.deleteUsers(List.of(user));
    }

    @Test
    void theFirstAccessCreatesTheAccountAndLaterOnesFindIt() throws SQLException {
        assertThat(register()).isTrue();
        assertThat(register()).isFalse();

        assertThat(IdentityTestDatabase.userStatus(user)).contains("ACTIVE");
    }

    @Test
    void simultaneousFirstAccessesAllSucceedAndCreateOneAccount() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(SIMULTANEOUS_REQUESTS)) {
            for (int i = 0; i < SIMULTANEOUS_REQUESTS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return register();
                }));
            }
            start.countDown();
            List<Boolean> created = new ArrayList<>();
            for (Future<Boolean> result : results) {
                created.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(created).containsOnlyOnce(true).hasSize(SIMULTANEOUS_REQUESTS);
        }
        assertThat(IdentityTestDatabase.userCount(user)).isEqualTo(1);
    }

    private boolean register() {
        boolean[] created = new boolean[1];
        TestSecurityContext.runAs(user, List.of(), () -> created[0] = accounts.registerCurrentUser());
        return created[0];
    }
}
