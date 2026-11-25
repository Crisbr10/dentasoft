-- Locks of scheduled tasks (M01-T08, ADR 0013): ShedLock keeps one row per task here, so a task runs once even
-- with several replicas.
--
-- Not clinic data, so it lives outside `public`, in a schema of its own, without Row Level Security.
-- RowLevelSecurityGuardTests treats it as an explicit exception: this schema, this single table and exactly
-- these grants. The application can read, create and update lock rows, nothing else: no DELETE, and it cannot
-- create objects in the schema.
--
-- The times are `timestamp`, not `timestamptz`, on purpose: with usingDbTime() ShedLock writes and compares
-- timezone('utc', CURRENT_TIMESTAMP), a timestamp without time zone. A `timestamptz` column would interpret it in
-- the TimeZone of each session, which the JDBC driver takes from the JVM, so two replicas in different time zones
-- would disagree on when a lock ends and could both run the task. Here every value is UTC.
--
-- To undo it by hand: DROP SCHEMA scheduler CASCADE;

CREATE SCHEMA scheduler;

-- A new schema grants nothing to PUBLIC; revoking makes the intent explicit.
REVOKE ALL ON SCHEMA scheduler FROM PUBLIC;
GRANT USAGE ON SCHEMA scheduler TO dentasoft_app;

CREATE TABLE scheduler.shedlock
(
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- The default privileges of V001 only cover `public`, so nothing has been granted yet. SELECT is needed because
-- taking a lock reads the row it updates (INSERT … ON CONFLICT DO UPDATE … WHERE lock_until <= now).
REVOKE ALL ON TABLE scheduler.shedlock FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE scheduler.shedlock TO dentasoft_app;
