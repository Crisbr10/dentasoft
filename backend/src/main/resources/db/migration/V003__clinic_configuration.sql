-- Configuration of a clinic by the clinic itself (M01-T06): its data, opening hours and treatment rooms.
--
-- Runs as dentasoft_owner.

-- What the application may do with its own clinic. By V001's default privileges it could insert into `clinic` and
-- update every column, so a bug in the application could create clinics, reactivate a suspended clinic, change its
-- keycloak_org_id or its tax data. The clinic only renames itself and changes its time zone; creating clinics and
-- changing their status is the platform's (V002). legal_name and tax_id are not the clinic's to change either: if
-- they need correcting, the platform does it (M01-T11).
-- Revoking at table level removes the table-wide privilege; the column grant then adds back only what is needed.
-- SELECT ... FOR NO KEY UPDATE (what Hibernate issues for a pessimistic write lock on PostgreSQL), with which the
-- application serializes changes to a clinic's hours, needs UPDATE on at least one column, which it keeps.
REVOKE INSERT, UPDATE ON clinic FROM dentasoft_app;
GRANT UPDATE (name, timezone, updated_at, updated_by, version) ON clinic TO dentasoft_app;

-- Opening hours of a clinic (FHIR Location.hoursOfOperation): several slots per day of the week, which must not
-- overlap (checked by the application, which locks the clinic's row while it replaces them).
--
-- Exception to the rule that dates are timestamptz: a slot is a wall-clock time of the clinic in its own time zone
-- (clinic.timezone), the same every week whatever the UTC offset, so `time` (without time zone) is the right type.
-- A point in time is still always timestamptz.
CREATE TABLE clinic_hours_of_operation
(
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES clinic (id),
    -- ISO 8601: 1 = Monday … 7 = Sunday, as java.time.DayOfWeek.
    day_of_week  smallint    NOT NULL,
    opening_time time        NOT NULL,
    closing_time time        NOT NULL,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT clinic_hours_of_operation_day_of_week_check CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT clinic_hours_of_operation_times_check CHECK (opening_time < closing_time)
);

CREATE INDEX clinic_hours_of_operation_tenant_id_day_idx
    ON clinic_hours_of_operation (tenant_id, day_of_week, opening_time);

SELECT enable_tenant_isolation('clinic_hours_of_operation');

-- The only table where the application deletes rows. The clinic replaces its whole week at once, and the old slots
-- are worth nothing once replaced: they are not clinical data (which is never deleted, V001), nobody refers to them,
-- and a soft delete would only pile up dead rows. Row Level Security limits the DELETE to the active clinic's rows.
-- No other table gets DELETE without the same reasoning; TenancyMigrationTests checks this is the only one.
GRANT DELETE ON clinic_hours_of_operation TO dentasoft_app;

-- Treatment rooms of a clinic (gabinetes), named after FHIR Location. Never deleted: a room that is no longer used is
-- made INACTIVE, so appointments that took place in it (M06) keep pointing at it.
CREATE TABLE location
(
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES clinic (id),
    name       varchar(100) NOT NULL,
    -- Colour of the room in the calendar, as #RRGGBB.
    color      varchar(7),
    status     varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz  NOT NULL,
    created_by uuid,
    updated_at timestamptz  NOT NULL,
    updated_by uuid,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT location_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT location_color_check CHECK (color ~ '^#[0-9A-F]{6}$')
);

CREATE INDEX location_tenant_id_name_idx ON location (tenant_id, name);

-- Two active rooms of a clinic never share a name, whatever the case. Inactive ones may: a room can be replaced by a
-- new one with the same name.
CREATE UNIQUE INDEX location_tenant_id_active_name_key ON location (tenant_id, lower(name)) WHERE status = 'ACTIVE';

SELECT enable_tenant_isolation('location');
