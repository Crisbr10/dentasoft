-- Practitioners of a clinic (M02-T06, ADR 0017).
--
-- Runs as dentasoft_owner. A practitioner_role is what a person does as a practitioner in one clinic, named after
-- FHIR PractitionerRole: their licence number, clinical area and colour depend on the clinic, as someone may work in
-- two clinics with another colour, or be registered with another provincial college. There is no table for the person
-- (FHIR Practitioner): when they have an account it is their user_account, whose personal data stays in Keycloak
-- (ADR 0015), and a table of people shared by every clinic would be personal data outside the clinic's isolation.
--
-- To undo it by hand, as the owner: DROP TABLE practitioner_role; DROP FUNCTION guard_practitioner_role_user();

CREATE TABLE practitioner_role
(
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES clinic (id),
    -- The user the record belongs to, when the person has an account. NULL for someone who never signs in, such as
    -- an external collaborator whose appointments reception books.
    user_id          uuid,
    -- How the clinic names the practitioner in its calendar, quotes and documents. Written by the clinic, not copied
    -- from Keycloak, and kept when the record is deactivated: past appointments and documents still show it.
    display_name     varchar(100) NOT NULL,
    -- Clinical area. Dentistry has no officially recognised specialties in Spain: user-facing texts call it «área
    -- clínica».
    specialty        varchar(30)  NOT NULL,
    -- INE code of the province of the professional college the licence number belongs to.
    license_province char(2),
    license_number   varchar(12),
    -- Colour of the practitioner in the calendar, as #RRGGBB.
    agenda_color     char(7)      NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz  NOT NULL,
    created_by       uuid,
    updated_at       timestamptz  NOT NULL,
    updated_by       uuid,
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT practitioner_role_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT practitioner_role_specialty_check
        CHECK (specialty IN ('GENERAL_DENTISTRY', 'ORTHODONTICS', 'ENDODONTICS', 'PERIODONTICS', 'IMPLANTOLOGY',
                             'ORAL_SURGERY', 'PROSTHODONTICS', 'PEDIATRIC_DENTISTRY', 'DENTAL_HYGIENE')),
    CONSTRAINT practitioner_role_agenda_color_check CHECK (agenda_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT practitioner_role_license_province_check
        CHECK (license_province ~ '^(0[1-9]|[1-4][0-9]|5[0-2])$'),
    CONSTRAINT practitioner_role_license_number_check
        CHECK (license_number ~ '^[0-9A-Z]+([-/][0-9A-Z]+)?$' AND length(license_number) BETWEEN 3 AND 12),
    -- A licence number always comes with its province, and the other way round.
    CONSTRAINT practitioner_role_license_check CHECK ((license_number IS NULL) = (license_province IS NULL)),
    -- Dental hygienists are not registered with a college as dentists are: only their licence number is optional.
    CONSTRAINT practitioner_role_license_required_check
        CHECK (specialty = 'DENTAL_HYGIENE' OR license_number IS NOT NULL),
    -- At most one record per user and clinic. Records without a user are not limited (NULLs are distinct).
    CONSTRAINT practitioner_role_tenant_id_user_id_key UNIQUE (tenant_id, user_id),
    -- A linked user is a member of the same clinic. Not checked when user_id is NULL (MATCH SIMPLE). Memberships are
    -- never deleted, so the reference never breaks.
    CONSTRAINT practitioner_role_membership_fkey FOREIGN KEY (tenant_id, user_id)
        REFERENCES clinic_membership (tenant_id, user_id)
);

CREATE INDEX practitioner_role_tenant_id_display_name_idx ON practitioner_role (tenant_id, display_name);

-- Two active records of a clinic never share a licence number of the same province. Inactive ones may: a practitioner
-- who left may come back with a new record.
CREATE UNIQUE INDEX practitioner_role_tenant_id_active_license_key
    ON practitioner_role (tenant_id, license_province, license_number)
    WHERE status = 'ACTIVE' AND license_number IS NOT NULL;

-- Never deleted (no DELETE, by V001's default privileges): appointments (M06) keep pointing at the record of the
-- practitioner who saw the patient. The application updates neither the id nor the creation columns.
REVOKE UPDATE ON practitioner_role FROM dentasoft_app;
GRANT UPDATE (tenant_id, user_id, display_name, specialty, license_province, license_number, agenda_color, status,
              updated_at, updated_by, version) ON practitioner_role TO dentasoft_app;

SELECT enable_tenant_isolation('practitioner_role');

-- A record never changes person: its user may be set once, when someone who had no account gets one, and never
-- changed nor removed after, or past appointments would show another person as the practitioner who saw the patient.
-- Only the owner (migrations, tests) is exempt, as in V011.
CREATE FUNCTION guard_practitioner_role_user()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    IF current_user <> 'dentasoft_owner' AND OLD.user_id IS NOT NULL
        AND NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'a practitioner record never changes person' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER practitioner_role_user_guard
    BEFORE UPDATE OF user_id
    ON practitioner_role
    FOR EACH ROW
EXECUTE FUNCTION guard_practitioner_role_user();
