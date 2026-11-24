package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_A;
import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_B;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The second layer on its own: Hibernate's clinic filter ({@code @TenantId}). With clinic A active, PostgreSQL is told
 * to let clinic B's rows through ({@link #simulateRlsLetsClinicThrough}), so only Hibernate stands between clinic A
 * and clinic B's row: no JPA lookup, query, change or delete may reach it. Every write check then reads, as the owner,
 * what is stored: an attempt that does not fail is not enough.
 *
 * <p>Each check throws an {@link AssertionError} when the layer lets something through.
 */
final class HibernateIsolationChecks<E extends BaseEntity> {

    private final IsolationFixture fixture;
    private final Class<E> type;
    private final JpaRepository<E, UUID> repository;
    private final Consumer<E> modify;
    private final EntityManager entityManager;
    private final JdbcClient jdbc;

    HibernateIsolationChecks(
            IsolationFixture fixture,
            Class<E> type,
            JpaRepository<E, UUID> repository,
            Consumer<E> modify,
            EntityManager entityManager,
            JdbcClient jdbc) {
        this.fixture = fixture;
        this.type = type;
        this.repository = repository;
        this.modify = modify;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    /**
     * A simulated Row Level Security failure, to test Hibernate's clinic filter on its own: makes PostgreSQL use
     * {@code clinic} for the rest of the current transaction, whatever the active clinic is. Only tests of that second
     * layer may call it: every other test must go through the real path.
     */
    static void simulateRlsLetsClinicThrough(JdbcClient jdbc, String table, UUID clinic) {
        jdbc.sql("SELECT set_config('app.tenant_id', ?, true)")
                .param(clinic.toString())
                .query(String.class)
                .single();
        // Native SQL is not filtered by Hibernate: the simulation works if it now sees the clinic's rows.
        assertThat(jdbc.sql("SELECT count(*) FROM %s WHERE tenant_id = ?"
                                .formatted(TenancyTestDatabase.checkedTable(table)))
                        .param(clinic)
                        .query(Long.class)
                        .single())
                .as("the Row Level Security simulation must let clinic %s's rows of %s through", clinic, table)
                .isPositive();
    }

    void hidesTheOtherClinic() {
        UUID other = fixture.otherRow();
        fixture.asClinic(CLINIC_A, () -> {
            letClinicBThrough();
            assertThat(repository.findAll())
                    .as("findAll() returned clinic B's rows of %s", type.getSimpleName())
                    .extracting(BaseEntity::getId)
                    .doesNotContain(other);
            assertThat(repository.findById(other))
                    .as("findById() returned clinic B's %s", type.getSimpleName())
                    .isEmpty();
            assertThat(entityManager.find(type, other))
                    .as("EntityManager.find() returned clinic B's %s", type.getSimpleName())
                    .isNull();
            // Whatever PostgreSQL lets through of clinic A's own rows, Hibernate must count those and nothing else.
            assertThat(repository.count())
                    .as("count() counted clinic B's rows of %s", type.getSimpleName())
                    .isEqualTo(jdbc.sql("SELECT count(*) FROM %s WHERE tenant_id = ?".formatted(fixture.table()))
                            .param(CLINIC_A)
                            .query(Long.class)
                            .single());
            assertThat(entityManager
                            .createQuery(
                                    "select count(e) from %s e where e.id = :id".formatted(entityName()), Long.class)
                            .setParameter("id", other)
                            .getSingleResult())
                    .as("a JPQL query returned clinic B's %s", type.getSimpleName())
                    .isZero();
            return null;
        });
    }

    void blocksUpdatesOfTheOtherClinic() {
        String before = fixture.storedOtherRow();
        E detached = fixture.asClinic(
                CLINIC_B, () -> repository.findById(fixture.otherRow()).orElseThrow());
        modify.accept(detached);

        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            return repository.saveAndFlush(detached);
        });
        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            E found = entityManager.find(type, fixture.otherRow());
            if (found != null) {
                modify.accept(found);
                entityManager.flush();
            }
            return null;
        });
        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            return entityManager
                    .createQuery("update %s e set e.updatedAt = :now where e.id = :id".formatted(entityName()))
                    .setParameter("now", Instant.now())
                    .setParameter("id", fixture.otherRow())
                    .executeUpdate();
        });

        assertThat(fixture.storedOtherRow())
                .as("clinic B's row after clinic A tried to change its %s through JPA", type.getSimpleName())
                .isEqualTo(before);
    }

    /**
     * {@code dentasoft_app} has DELETE only on {@code clinic_hours_of_operation} (V003): there Hibernate must stop it;
     * elsewhere the missing grant does too. Either way, this proves the row survives, whichever layer stops it.
     */
    void blocksDeletesOfTheOtherClinic() {
        String before = fixture.storedOtherRow();
        E detached = fixture.asClinic(
                CLINIC_B, () -> repository.findById(fixture.otherRow()).orElseThrow());

        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            repository.deleteById(fixture.otherRow());
            repository.flush();
            return null;
        });
        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            repository.delete(detached);
            repository.flush();
            return null;
        });
        fixture.attemptAsClinicA(() -> {
            letClinicBThrough();
            return entityManager
                    .createQuery("delete from %s e where e.id = :id".formatted(entityName()))
                    .setParameter("id", fixture.otherRow())
                    .executeUpdate();
        });

        assertThat(fixture.storedOtherRow())
                .as("clinic B's row after clinic A tried to delete its %s through JPA", type.getSimpleName())
                .isEqualTo(before);
    }

    private void letClinicBThrough() {
        simulateRlsLetsClinicThrough(jdbc, fixture.table(), CLINIC_B);
    }

    private String entityName() {
        return entityManager.getMetamodel().entity(type).getName();
    }
}
