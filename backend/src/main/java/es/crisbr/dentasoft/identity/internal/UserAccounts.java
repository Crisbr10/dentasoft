package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.shared.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account and memberships of the signed-in user ({@code user_account}, {@code clinic_membership}, V007), with SQL:
 * Hibernate filters every query on memberships by the active clinic, and these are read before there is one. Row Level
 * Security limits both tables to the user's own rows ({@code app.user_id}), whatever the SQL asks for.
 */
@Service
@PreAuthorize("isAuthenticated()")
public class UserAccounts {

    private final JdbcClient jdbc;

    UserAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One of the user's active memberships. */
    public record ActiveMembership(UUID clinicId, ClinicRole role) {}

    /**
     * Creates the account of the signed-in user on their first access, with nothing but the Keycloak user ID. Two first
     * requests at the same time both succeed: the second insert finds the row and does nothing.
     *
     * @return whether the account was created by this call
     */
    @Transactional
    public boolean registerCurrentUser() {
        UUID user = CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user to register"));
        return jdbc.sql("INSERT INTO user_account (id, status, created_at, created_by, updated_at, updated_by)"
                                + " VALUES (:id, 'ACTIVE', now(), :id, now(), :id) ON CONFLICT (id) DO NOTHING")
                        .param("id", user)
                        .update()
                == 1;
    }

    /**
     * The signed-in user's active memberships, in every clinic. Empty when the account is disabled or missing: such a
     * user belongs to no clinic.
     */
    @Transactional(readOnly = true)
    public List<ActiveMembership> activeMembershipsOfCurrentUser() {
        UUID user = CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user"));
        return jdbc.sql("SELECT m.tenant_id, m.role FROM clinic_membership m"
                        + " JOIN user_account u ON u.id = m.user_id"
                        + " WHERE m.user_id = :user AND m.active AND u.status = 'ACTIVE'")
                .param("user", user)
                .query((row, rowNumber) -> new ActiveMembership(
                        row.getObject("tenant_id", UUID.class), ClinicRole.valueOf(row.getString("role"))))
                .list();
    }

    /**
     * The signed-in user's role in {@code clinicId}, if they have an active membership there and their account is
     * active. Row Level Security only shows the user's own memberships, so another user's never counts.
     */
    @Transactional(readOnly = true)
    public Optional<ClinicRole> activeRoleIn(UUID clinicId) {
        UUID user = CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user"));
        return jdbc.sql("SELECT m.role FROM clinic_membership m"
                        + " JOIN user_account u ON u.id = m.user_id"
                        + " WHERE m.user_id = :user AND m.tenant_id = :clinic AND m.active AND u.status = 'ACTIVE'")
                .param("user", user)
                .param("clinic", clinicId)
                .query((row, rowNumber) -> ClinicRole.valueOf(row.getString("role")))
                .optional();
    }
}
