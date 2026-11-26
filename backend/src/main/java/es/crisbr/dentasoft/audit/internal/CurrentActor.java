package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditActorType;
import es.crisbr.dentasoft.shared.CurrentUser;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting now, for the entries of the main persistence unit.
 *
 * <p>The authenticated user is {@link AuditActorType#USER}, with the Keycloak {@code sub} of the access token, the same
 * ID {@code created_by} records ({@code AuditingConfiguration}). Without one (scheduled jobs, asynchronous listeners,
 * the {@code ClinicBatch} processes of M01-T08) it is {@link AuditActorType#SYSTEM}, without an id. Platform entries
 * never come through here: the platform writes them itself ({@code PlatformAuditRecord}).
 */
record CurrentActor(AuditActorType type, @Nullable UUID id) {

    private static final CurrentActor SYSTEM = new CurrentActor(AuditActorType.SYSTEM, null);

    static CurrentActor get() {
        return CurrentUser.id()
                .map(user -> new CurrentActor(AuditActorType.USER, user))
                .orElse(SYSTEM);
    }
}
