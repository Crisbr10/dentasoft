package es.crisbr.dentasoft.tenancy;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The name and status of clinics the current user belongs to, also without an active clinic: what {@code GET
 * /api/v1/me} shows. Row Level Security decides what is found: the {@code member_select} policy (V007) only lets
 * through the clinics in which the user ({@code app.user_id}) has an active membership, so asking for any other
 * clinic finds nothing.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ClinicDirectory {

    private final JdbcClient jdbc;

    ClinicDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A clinic as its members see it before choosing it. */
    public record ClinicSummary(UUID id, String name, boolean suspended) {}

    /** The clinics among {@code ids} that the current user can see, in no particular order. */
    @Transactional(readOnly = true)
    public List<ClinicSummary> visibleClinics(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name, status FROM clinic WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, rowNumber) -> new ClinicSummary(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        "SUSPENDED".equals(row.getString("status"))))
                .list();
    }
}
