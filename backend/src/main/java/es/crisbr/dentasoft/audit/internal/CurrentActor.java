package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditActorType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting now, for the entries of the main persistence unit.
 *
 * <p>Until M02 there are no users: every entry is {@link AuditActorType#SYSTEM}, without an id, just as {@code
 * created_by} stays empty ({@code AuditingConfiguration}). M02 returns {@link AuditActorType#USER} and the Keycloak
 * {@code sub} of the authenticated user. Platform entries never come through here: the platform writes them itself
 * ({@code PlatformAuditRecord}).
 */
record CurrentActor(AuditActorType type, @Nullable UUID id) {

    private static final CurrentActor SYSTEM = new CurrentActor(AuditActorType.SYSTEM, null);

    static CurrentActor get() {
        return SYSTEM;
    }
}
