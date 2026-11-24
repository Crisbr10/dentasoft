package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.tenancy.internal.ActiveClinicResolver;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The active clinic, held in a {@link ScopedValue}: it exists only inside the block passed to {@link #runAs} or
 * {@link #callAs} and disappears when the block ends, even if it fails. There is no {@code set()}, so a clinic can
 * never stay on a thread and reach another request or task.
 *
 * <p>The clinic is not inherited: tasks sent to an executor, new threads, {@code @Async} methods and
 * {@code @ApplicationModuleListener} listeners start without one and must bind their own.
 *
 * <p>Two rules keep the clinic of the Hibernate session equal to the active one, since the session takes the clinic
 * when it opens, at the start of the outermost transaction:
 *
 * <ul>
 *   <li>a block cannot switch to another clinic while one is active;
 *   <li>a clinic cannot be bound inside a transaction that started without one. Bind it first, then start the
 *       transaction.
 * </ul>
 *
 * <p>Until membership validation exists (M02), the clinic is never taken from a header, a path or a request body.
 */
public final class TenantContext {

    private static final ScopedValue<UUID> ACTIVE_CLINIC = ScopedValue.newInstance();

    private TenantContext() {}

    /** Runs {@code action} with {@code clinicId} as the active clinic. */
    public static void runAs(UUID clinicId, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (enter(clinicId)) {
            action.run();
        } else {
            ScopedValue.where(ACTIVE_CLINIC, clinicId).run(action);
        }
    }

    /** Runs {@code operation} with {@code clinicId} as the active clinic and returns its result. */
    public static <T, X extends Throwable> T callAs(UUID clinicId, ScopedValue.CallableOp<? extends T, X> operation)
            throws X {
        Objects.requireNonNull(operation, "operation");
        if (enter(clinicId)) {
            return operation.call();
        }
        return ScopedValue.where(ACTIVE_CLINIC, clinicId).call(operation);
    }

    /** The active clinic. */
    public static UUID current() {
        if (!ACTIVE_CLINIC.isBound()) {
            throw new NoActiveClinicException();
        }
        return ACTIVE_CLINIC.get();
    }

    /** Whether a clinic is active on this thread. */
    public static boolean isBound() {
        return ACTIVE_CLINIC.isBound();
    }

    /**
     * Checks that {@code clinicId} can become the active clinic.
     *
     * @return {@code true} when it already is, so the block runs as it is
     */
    private static boolean enter(UUID clinicId) {
        if (clinicId == null || ActiveClinicResolver.NO_CLINIC.equals(clinicId)) {
            throw new IllegalArgumentException("A clinic ID is required");
        }
        if (ACTIVE_CLINIC.isBound()) {
            if (!ACTIVE_CLINIC.get().equals(clinicId)) {
                throw new IllegalStateException("Cannot switch to another clinic inside an active one");
            }
            return true;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Cannot bind a clinic inside a transaction started without one: bind it before the transaction");
        }
        return false;
    }
}
