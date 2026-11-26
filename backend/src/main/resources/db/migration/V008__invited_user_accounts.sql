-- Accounts of invited users (M02-T05, ADR 0016).
--
-- Runs as dentasoft_owner. clinic_membership.user_id references user_account (V007), but user_isolation only lets a
-- user create their own row, so an administrator inviting someone could not create the membership. The policy below
-- lets the application create the account of another user, with three limits checked by PostgreSQL:
--
-- - only an ACTIVE account: never a disabled one, and an existing account stays as it is (the application still has
--   no UPDATE on the table, V007);
-- - only by an active ADMIN_CLINICA of the active clinic (app.tenant_id), the only users who invite: requests without
--   a clinic, other roles and processes without a user create nothing, whatever the application does;
-- - created_by and updated_by are the user making the request (app.user_id), so the audit columns name who created it.
--
-- It only adds INSERT. Reading, changing or deleting another user's account stays impossible: user_isolation keeps
-- deciding SELECT, UPDATE and DELETE, and the application has no UPDATE or DELETE privilege on the table.
--
-- To undo it by hand, as the owner: DROP FUNCTION create_invited_user_account(uuid); DROP POLICY invited_insert ON
-- user_account;

CREATE POLICY invited_insert ON user_account
    FOR INSERT
    TO dentasoft_app
    WITH CHECK (
    status = 'ACTIVE'
        AND created_by = NULLIF(current_setting('app.user_id', true), '')::uuid
        AND updated_by = created_by
        AND EXISTS (SELECT 1
                    FROM clinic_membership m
                    WHERE m.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                      AND m.user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
                      AND m.active
                      AND m.role = 'ADMIN_CLINICA')
    );

-- Creates the account of an invited user unless it exists, in the name of the requesting user. INSERT … ON CONFLICT DO
-- NOTHING cannot do it: when the existing row is not visible (another user's account, the usual case of someone who
-- already works in another clinic) PostgreSQL refuses the insert instead of skipping it. A plain INSERT reaches the
-- unique key instead, and its error is caught here, in a subtransaction, so the caller's transaction goes on.
--
-- SECURITY INVOKER: it runs as the application, under the policy above, and reveals nothing (it returns nothing).
CREATE FUNCTION create_invited_user_account(p_user_id uuid)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, pg_temp
AS
$$
DECLARE
    v_actor uuid := NULLIF(current_setting('app.user_id', true), '')::uuid;
BEGIN
    INSERT INTO public.user_account (id, status, created_at, created_by, updated_at, updated_by)
    VALUES (p_user_id, 'ACTIVE', now(), v_actor, now(), v_actor);
EXCEPTION
    WHEN unique_violation THEN
        NULL;
END;
$$;

REVOKE EXECUTE ON FUNCTION create_invited_user_account(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_invited_user_account(uuid) TO dentasoft_app;
