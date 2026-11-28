-- The platform invites the first administrator of a clinic (M02-T11, ADR 0018).
--
-- Runs as dentasoft_owner. A new clinic has no members, and only an accepted ADMIN_CLINICA of a clinic invites people
-- to it (V010), so nobody could invite its first administrator. The platform administrator does, as
-- dentasoft_platform, through the policies below. They all name that role (TO dentasoft_platform), so nothing changes
-- for dentasoft_app: invited_insert (V010) stays as it is.
--
-- What the platform may do, and nothing more:
--
-- - create the invited person's account, active and in its own name (created_by = app.user_id), like V010 does for
--   an administrator; it cannot read, change or delete accounts;
-- - see the ADMIN_CLINICA memberships of every clinic, only the columns it needs (who is the administrator, and
--   whether and when they were invited and accepted), so it knows whether a clinic has one, shows the invitation's
--   state and can resend or cancel it;
-- - create a pending ADMIN_CLINICA membership, only while the clinic has no active ADMIN_CLINICA, accepted or pending
--   (the trigger below). Two invitations at the same time run one after the other, and the second fails;
-- - cancel a pending invitation, or make a cancelled one active again, changing only active and the audit columns.
--   It never writes accepted_at or role: only the invited person accepts (V009, V011). Once accepted, a membership is
--   out of its reach;
-- - record what it does in audit_log, also about those memberships.
--
-- To undo it by hand, as the owner: DROP TRIGGER platform_first_admin ON clinic_membership; DROP FUNCTION
-- guard_platform_first_admin(); DROP POLICY platform_select ON clinic_membership; DROP POLICY platform_insert ON
-- clinic_membership; DROP POLICY platform_update ON clinic_membership; DROP POLICY platform_insert ON user_account;
-- REVOKE ALL ON clinic_membership, user_account FROM dentasoft_platform; REVOKE EXECUTE ON FUNCTION
-- create_invited_user_account(uuid) FROM dentasoft_platform; and restore V004's platform_insert on audit_log with
-- ALTER POLICY … WITH CHECK (…).

-- The invited person's account. create_invited_user_account (V008) is SECURITY INVOKER: it runs under this policy, and
-- skips an account that already exists, which the platform cannot see.
GRANT INSERT (id, status, created_at, created_by, updated_at, updated_by) ON user_account TO dentasoft_platform;

CREATE POLICY platform_insert ON user_account
    FOR INSERT
    TO dentasoft_platform
    WITH CHECK (
    status = 'ACTIVE'
        AND created_by = NULLIF(current_setting('app.user_id', true), '')::uuid
        AND updated_by = created_by
    );

GRANT EXECUTE ON FUNCTION create_invited_user_account(uuid) TO dentasoft_platform;

-- The administrators of each clinic: only these columns, and only their rows. updated_at is when a pending invitation
-- was sent (created or reactivated: resending does not change it), which the platform shows; accepted_at, when it was
-- accepted.
GRANT SELECT (id, tenant_id, user_id, role, active, accepted_at, updated_at, version)
    ON clinic_membership TO dentasoft_platform;

CREATE POLICY platform_select ON clinic_membership
    FOR SELECT
    TO dentasoft_platform
    USING (role = 'ADMIN_CLINICA');

-- A new invitation: an active, pending ADMIN_CLINICA membership in the platform administrator's name. accepted_at is
-- not granted, so it is always NULL; the check repeats it.
GRANT INSERT (id, tenant_id, user_id, role, active, created_at, created_by, updated_at, updated_by, version)
    ON clinic_membership TO dentasoft_platform;

CREATE POLICY platform_insert ON clinic_membership
    FOR INSERT
    TO dentasoft_platform
    WITH CHECK (
    role = 'ADMIN_CLINICA'
        AND active
        AND accepted_at IS NULL
        AND created_by = NULLIF(current_setting('app.user_id', true), '')::uuid
        AND updated_by = created_by
    );

-- Cancelling a pending invitation (active = false) or inviting the same person again (active = true). Only pending
-- rows, before and after, so an accepted membership cannot be changed, and the person accepting at the same time wins
-- or loses as a whole: the row lock makes one UPDATE wait, and PostgreSQL checks USING again on the row it then finds.
GRANT UPDATE (active, updated_at, updated_by, version) ON clinic_membership TO dentasoft_platform;

CREATE POLICY platform_update ON clinic_membership
    FOR UPDATE
    TO dentasoft_platform
    USING (role = 'ADMIN_CLINICA' AND accepted_at IS NULL)
    WITH CHECK (
    role = 'ADMIN_CLINICA'
        AND accepted_at IS NULL
        AND updated_by = NULLIF(current_setting('app.user_id', true), '')::uuid
    );

-- What a policy cannot express: "only while the clinic has no administrator", safely against concurrent requests. When
-- the platform makes an ADMIN_CLINICA membership active (a new invitation, or a cancelled one again), the trigger
-- first locks the clinic's row, so two of them for the same clinic run one after the other, and then refuses it if
-- the clinic already has an active ADMIN_CLINICA, accepted or pending. In READ COMMITTED each statement of the
-- function takes a new snapshot, so the second one, once the lock is free, sees the membership the first committed.
--
-- SECURITY INVOKER: it runs as the platform, under its policies and grants (SELECT … FOR UPDATE on clinic needs its
-- UPDATE privilege, and it sees every ADMIN_CLINICA membership). Other roles are not limited by it: an administrator
-- of the clinic (dentasoft_app) invites other administrators (M02-T05).
CREATE FUNCTION guard_platform_first_admin()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    IF current_user <> 'dentasoft_platform' OR NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    PERFORM 1 FROM public.clinic c WHERE c.id = NEW.tenant_id FOR UPDATE;
    IF EXISTS (SELECT 1
               FROM public.clinic_membership m
               WHERE m.tenant_id = NEW.tenant_id
                 AND m.role = 'ADMIN_CLINICA'
                 AND m.active
                 AND m.id <> NEW.id) THEN
        RAISE EXCEPTION 'the clinic already has an administrator or a pending invitation of one'
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'clinic_first_admin';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION guard_platform_first_admin() FROM PUBLIC;

CREATE TRIGGER platform_first_admin
    BEFORE INSERT OR UPDATE OF active
    ON clinic_membership
    FOR EACH ROW
EXECUTE FUNCTION guard_platform_first_admin();

-- The platform also records what it does to those memberships, under their clinic: entity_id must be a membership of
-- that same clinic (one it can see, so an ADMIN_CLINICA one). Entries about the clinic itself stay as in V004.
ALTER POLICY platform_insert ON audit_log
    WITH CHECK (
    actor_type = 'PLATFORM'
        AND action IN ('CREATE', 'UPDATE')
        AND ((entity_type = 'CLINIC' AND entity_id = tenant_id)
        OR (entity_type = 'CLINIC_MEMBERSHIP'
            AND EXISTS (SELECT 1
                        FROM clinic_membership m
                        WHERE m.id = audit_log.entity_id
                          AND m.tenant_id = audit_log.tenant_id)))
    );
