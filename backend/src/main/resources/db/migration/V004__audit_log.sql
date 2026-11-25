-- Audit log of accesses to and changes of sensitive data (M01-T07, ADR 0012), after FHIR AuditEvent.
--
-- Runs as dentasoft_owner.
--
-- The log is append-only for everyone: the application may only insert (no SELECT until M01-T09, no UPDATE, no
-- DELETE), and a trigger rejects UPDATE, DELETE and TRUNCATE even for the owner, so rewriting history takes a
-- migration that drops the trigger first, in plain sight. Whoever inserts chooses neither the clinic nor the time:
-- both come from the database.
--
-- No column holds free text: every one is a UUID, a timestamp or a code from a closed list. `details` is written
-- only from the AuditDetails record, whose components can only be UUIDs, codes, numbers or booleans
-- (AuditDetailsTests). The client IP is not stored: request_id correlates an entry with the request.
CREATE TABLE audit_log
(
    id          uuid PRIMARY KEY,
    -- The active clinic of the transaction: whoever inserts does not choose it. Without an active clinic it is NULL
    -- and the insert fails, so no access goes unrecorded silently. The platform names the affected clinic itself
    -- (platform_insert below). No foreign key to clinic: the log must outlive the row it talks about.
    tenant_id   uuid        NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::uuid,
    -- The database's clock, not the caller's: an entry cannot be backdated.
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- Who did it (FHIR AuditEvent.agent): a user of the clinic, the system (jobs, webhooks) or the platform.
    actor_type  text        NOT NULL,
    -- The Keycloak `sub` of the user: empty until M02.
    actor_id    uuid,
    action      text        NOT NULL,
    -- What it was done to (FHIR AuditEvent.entity): a code of AuditEntityType and the id of the row.
    entity_type text        NOT NULL,
    entity_id   uuid        NOT NULL,
    outcome     text        NOT NULL,
    -- The X-Request-Id of the request, only when the server generated it: an id the client chose, even a UUID,
    -- could repeat another request's, so it is never stored.
    request_id  uuid,
    details     jsonb,
    CONSTRAINT audit_log_actor_type_check CHECK (actor_type IN ('USER', 'SYSTEM', 'PLATFORM')),
    CONSTRAINT audit_log_user_actor_check CHECK (actor_type <> 'USER' OR actor_id IS NOT NULL),
    CONSTRAINT audit_log_action_check CHECK (action IN ('CREATE', 'READ', 'UPDATE', 'DELETE')),
    CONSTRAINT audit_log_entity_type_check CHECK (entity_type ~ '^[A-Z][A-Z_]{0,39}$'),
    CONSTRAINT audit_log_outcome_check CHECK (outcome IN ('SUCCESS', 'DENIED')),
    CONSTRAINT audit_log_details_check CHECK (details IS NULL OR jsonb_typeof(details) = 'object')
);

-- What M01-T09 queries: a clinic's log by date.
CREATE INDEX audit_log_tenant_id_occurred_at_idx ON audit_log (tenant_id, occurred_at);

SELECT enable_tenant_isolation('audit_log');

-- V001's default privileges gave the application SELECT, INSERT and UPDATE on the whole table. It keeps only INSERT,
-- and only of the columns it fills: tenant_id and occurred_at take their defaults, and the application never writes
-- details, so not even hand-written SQL can put text there. M01-T09 grants SELECT to read a clinic's own log.
REVOKE SELECT, INSERT, UPDATE ON audit_log FROM dentasoft_app;
GRANT INSERT (id, actor_type, actor_id, action, entity_type, entity_id, outcome, request_id) ON audit_log
    TO dentasoft_app;

-- Rejects every change to logged entries, whoever tries it: the owner too, as triggers run for every role.
-- TRUNCATE needs its own statement trigger: row triggers do not fire for it.
-- Not covered (see ADR 0012): dropping or disabling the trigger, which takes a migration; a superuser session with
-- session_replication_role = replica; and dropping a whole partition, if the table is ever partitioned.
CREATE FUNCTION audit_log_reject_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_log_reject_update_delete
    BEFORE UPDATE OR DELETE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION audit_log_reject_change();

CREATE TRIGGER audit_log_reject_truncate
    BEFORE TRUNCATE
    ON audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION audit_log_reject_change();

-- The platform records what it does to a clinic (create, suspend, reactivate) under that clinic, in the same
-- transaction: it has no active clinic, so it names tenant_id itself. It may only write what it does: entries of its
-- own, about the clinic they are stored under, creating or changing it. It cannot read the log.
GRANT INSERT (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, outcome, request_id, details)
    ON audit_log TO dentasoft_platform;

CREATE POLICY platform_insert ON audit_log FOR INSERT TO dentasoft_platform
    WITH CHECK (actor_type = 'PLATFORM' AND entity_type = 'CLINIC' AND entity_id = tenant_id
        AND action IN ('CREATE', 'UPDATE'));
