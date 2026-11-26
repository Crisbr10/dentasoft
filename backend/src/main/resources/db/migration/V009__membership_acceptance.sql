-- Invitations accepted by the invited person (M02-T05, ADR 0016).
--
-- Runs as dentasoft_owner. An invitation creates an active membership that grants nothing until its user accepts it:
-- nobody is added to a clinic without saying yes, and until then the clinic's administrators see the same thing for
-- everyone they invite, whether the person already used Dentasoft in another clinic or not. accepted_at is when the
-- user accepted; NULL means the invitation is pending.
--
-- To undo it by hand, as the owner: DROP TRIGGER membership_acceptance ON clinic_membership; DROP FUNCTION
-- guard_membership_acceptance(); ALTER TABLE clinic_membership DROP COLUMN accepted_at;

ALTER TABLE clinic_membership
    ADD COLUMN accepted_at timestamptz;

-- The memberships made before invitations existed (by hand, in local and test databases) count as accepted. The table
-- forces Row Level Security also on its owner, and a migration has no active clinic, so the policy is lifted for this
-- update only, inside the migration's transaction.
ALTER TABLE clinic_membership NO FORCE ROW LEVEL SECURITY;
UPDATE clinic_membership
SET accepted_at = created_at;
ALTER TABLE clinic_membership FORCE ROW LEVEL SECURITY;

-- The user accepts their invitation through the application, which therefore needs to write the column.
GRANT UPDATE (accepted_at) ON clinic_membership TO dentasoft_app;

-- What the policies cannot express, because they do not see the old row: for the application, a membership is born
-- pending, and only its own user accepts it, once. Making it pending again (an administrator inviting a former member
-- once more) is allowed. Other roles (the owner, in migrations and tests) are not limited.
CREATE FUNCTION guard_membership_acceptance()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    IF current_user <> 'dentasoft_app' OR NEW.accepted_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'a membership is created pending' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
        AND (OLD.accepted_at IS NOT NULL
            OR NEW.user_id IS DISTINCT FROM NULLIF(current_setting('app.user_id', true), '')::uuid) THEN
        RAISE EXCEPTION 'only its user accepts a membership, once' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE EXECUTE ON FUNCTION guard_membership_acceptance() FROM PUBLIC;

CREATE TRIGGER membership_acceptance
    BEFORE INSERT OR UPDATE OF accepted_at
    ON clinic_membership
    FOR EACH ROW
EXECUTE FUNCTION guard_membership_acceptance();
