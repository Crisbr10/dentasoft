package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationDetails;
import es.crisbr.dentasoft.tenancy.persistence.LocationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two administrators create a room with the same name at the same time (M01-T06). The second passes the name check,
 * because the first has not committed yet, and the unique index stops it instead: the answer must still be the
 * check's 409 ({@link LocationNameTakenException}), not a 500.
 *
 * <p>The first holds its transaction open until the second's insert is waiting on the index, and only then commits.
 */
class LocationNameRaceTests extends ClinicConfigurationTestBase {

    @Autowired
    private Locations locations;

    @Autowired
    private TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void theUniqueIndexRefusesTheSecondOfTwoSimultaneousRoomsWithTheName() throws Exception {
        AtomicInteger secondPid = new AtomicInteger();
        CountDownLatch firstCreated = new CountDownLatch(1);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<LocationDetails> first = threads.submit(asAdministrator(() -> {
                LocationDetails room = locations.create(new LocationData("Gabinete 1", null));
                firstCreated.countDown();
                DatabaseLocks.awaitWaitingForALock(secondPid::get);
                return room;
            }));
            Future<LocationDetails> second = threads.submit(asAdministrator(() -> {
                secondPid.set(DatabaseLocks.backendPid(entityManager));
                if (!firstCreated.await(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The first room was not created");
                }
                return locations.create(new LocationData("gabinete 1", null));
            }));

            assertThat(first.get(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                            .name())
                    .isEqualTo("Gabinete 1");
            assertThatThrownBy(() -> second.get(DatabaseLocks.TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(LocationNameTakenException.class);
        } finally {
            threads.shutdownNow();
        }

        assertThat(asClinicA(() -> locations.list(LocationStatus.ACTIVE, PageRequest.of(0, 20, Sort.by("name"))))
                        .getContent())
                .extracting(LocationDetails::name)
                .containsExactly("Gabinete 1");
    }

    /** As clinic A's administrator, in a transaction of its own that {@code operation}'s calls take part in. */
    private <T> Callable<T> asAdministrator(Callable<T> operation) {
        return new DelegatingSecurityContextCallable<>(() -> TenantContext.callAs(
                CLINIC_A,
                () -> transaction.execute(status -> {
                    try {
                        return operation.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })));
    }
}
