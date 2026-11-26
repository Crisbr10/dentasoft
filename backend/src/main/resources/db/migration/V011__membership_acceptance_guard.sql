-- A stricter guard on accepting invitations (M02-T05, ADR 0016).
--
-- Runs as dentasoft_owner. V009's trigger left two gaps, found by the task's verification:
--
-- - it limited only dentasoft_app, so any future role with write grants on clinic_membership would skip it; now only
--   the owner (migrations, tests) is exempt;
-- - the person accepting could change their role or status in the same UPDATE; accepting now changes nothing else.
--
-- To undo it by hand, as the owner: run V009's CREATE FUNCTION guard_membership_acceptance() again, as CREATE OR
-- REPLACE.

CREATE OR REPLACE FUNCTION guard_membership_acceptance()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    IF current_user = 'dentasoft_owner' OR NEW.accepted_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'a membership is created pending' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
        AND (OLD.accepted_at IS NOT NULL
            OR NEW.user_id IS DISTINCT FROM NULLIF(current_setting('app.user_id', true), '')::uuid
            OR NEW.role IS DISTINCT FROM OLD.role
            OR NEW.active IS DISTINCT FROM OLD.active) THEN
        RAISE EXCEPTION 'only its user accepts a membership, once, and nothing else changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
