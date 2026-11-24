-- Clinic administration by the platform (M01-T05, ADR 0011).
--
-- Runs as dentasoft_owner. The role dentasoft_platform must already exist: it is created outside Flyway, because
-- dentasoft_owner cannot create roles (infra/postgres/init/02-platform-role.sh locally,
-- docs/operaciones/rol-de-plataforma.md elsewhere).
--
-- dentasoft_platform is NOBYPASSRLS and the application role is never a member of it. It reaches every clinic only
-- through the policies below, which name it explicitly (TO dentasoft_platform), so they add nothing for
-- dentasoft_app or any other role. Policies are one per command: no DELETE policy and no DELETE privilege, so even
-- a mistaken grant would not let it delete a clinic.

-- The nil UUID is ActiveClinicResolver.NO_CLINIC, the "no clinic" sentinel: no clinic may ever have it as its id.
ALTER TABLE clinic
    ADD CONSTRAINT clinic_id_not_nil CHECK (id <> '00000000-0000-0000-0000-000000000000');

-- Settings of each clinic, which the clinic itself edits (M01-T06). Secrets never live here: channel credentials
-- (SMTP, WhatsApp) go to their own encrypted table in M07-T07, where dentasoft_platform gets no privilege at all.
CREATE TABLE clinic_settings
(
    id                          uuid PRIMARY KEY,
    tenant_id                   uuid         NOT NULL REFERENCES clinic (id),
    default_appointment_minutes smallint     NOT NULL DEFAULT 30,
    contact_email               varchar(254),
    contact_phone               varchar(32),
    created_at                  timestamptz  NOT NULL,
    created_by                  uuid,
    updated_at                  timestamptz  NOT NULL,
    updated_by                  uuid,
    version                     bigint       NOT NULL DEFAULT 0,
    -- One row per clinic. Its index also starts with tenant_id, as every business table needs.
    CONSTRAINT clinic_settings_tenant_id_key UNIQUE (tenant_id),
    CONSTRAINT clinic_settings_default_appointment_minutes_check
        CHECK (default_appointment_minutes BETWEEN 5 AND 480)
);

SELECT enable_tenant_isolation('clinic_settings');

-- What the platform does with clinics: list them, create them and change their status. It never edits a clinic's
-- data (the clinic does, M01-T06) nor sets keycloak_org_id (M02).
GRANT SELECT ON clinic TO dentasoft_platform;
GRANT INSERT (id, name, legal_name, tax_id, timezone, locale, status, created_at, created_by, updated_at, updated_by,
              version) ON clinic TO dentasoft_platform;
GRANT UPDATE (status, updated_at, updated_by, version) ON clinic TO dentasoft_platform;

CREATE POLICY platform_select ON clinic FOR SELECT TO dentasoft_platform USING (true);
CREATE POLICY platform_insert ON clinic FOR INSERT TO dentasoft_platform WITH CHECK (true);
CREATE POLICY platform_update ON clinic FOR UPDATE TO dentasoft_platform USING (true) WITH CHECK (true);

-- The platform only creates the initial settings of a new clinic, with the column defaults: it cannot set any
-- value of its own, read them back nor change them.
GRANT INSERT (id, tenant_id, created_at, created_by, updated_at, updated_by, version)
    ON clinic_settings TO dentasoft_platform;

CREATE POLICY platform_insert ON clinic_settings FOR INSERT TO dentasoft_platform WITH CHECK (true);
