-- Only an administrator of the clinic creates an invited user's account (M02-T05, ADR 0016).
--
-- Runs as dentasoft_owner. V008's invited_insert only required an active clinic and created_by = app.user_id. It now
-- also requires that app.user_id is an active, accepted ADMIN_CLINICA of app.tenant_id, the only users who invite, and
-- that updated_by is the same user, so the database checks it whatever the application does.
--
-- To undo it by hand, as the owner, restore V008's expression with ALTER POLICY invited_insert ON user_account WITH
-- CHECK (…).

ALTER POLICY invited_insert ON user_account
    WITH CHECK (
    status = 'ACTIVE'
        AND created_by = NULLIF(current_setting('app.user_id', true), '')::uuid
        AND updated_by = created_by
        AND EXISTS (SELECT 1
                    FROM clinic_membership m
                    WHERE m.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                      AND m.user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
                      AND m.active
                      AND m.accepted_at IS NOT NULL
                      AND m.role = 'ADMIN_CLINICA')
    );
