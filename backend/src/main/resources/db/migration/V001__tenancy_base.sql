-- Base of the multi-clinic model (M01-T01, ADR 0002).
--
-- Runs as dentasoft_owner (spring.flyway.user). The application connects as dentasoft_app, which is
-- NOBYPASSRLS and owns nothing (infra/postgres/init/01-init.sh), so Row Level Security always applies to it.
-- How to add a table with RLS: docs/plan/M01-clinicas-multitenant.md.

-- pgvector is not a trusted extension: dentasoft_owner cannot create it, so the superuser does
-- (01-init.sh locally, the platform elsewhere). IF NOT EXISTS makes this a no-op that documents the need.
-- pgcrypto is not needed: gen_random_uuid() is core since PostgreSQL 13 and UUID v7 ids are generated
-- by the application.
CREATE EXTENSION IF NOT EXISTS vector;

-- Every table dentasoft_owner creates from now on in `public` is readable and writable by the application.
-- No DELETE or TRUNCATE: clinical data is soft-deleted (deleted_at). Grant DELETE table by table when a
-- table really needs it.
ALTER DEFAULT PRIVILEGES FOR ROLE dentasoft_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE ON TABLES TO dentasoft_app;

-- PostgreSQL lets PUBLIC execute every new function. Functions are granted one by one instead, so a future
-- SECURITY DEFINER function is never callable by the application by accident. Triggers do not need it.
-- Without IN SCHEMA on purpose: per-schema default privileges can only add to the global ones, not revoke them.
ALTER DEFAULT PRIVILEGES FOR ROLE dentasoft_owner
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Flyway creates its history table before running this migration, so the default privileges above do not
-- reach it; revoking makes the intent explicit. The application never reads or writes the migration history.
REVOKE ALL ON TABLE ${flyway:defaultSchema}.${flyway:table} FROM PUBLIC, dentasoft_app;

-- Turns on and forces Row Level Security on a table and creates its `tenant_isolation` policy, which only
-- lets through rows whose p_column equals the active clinic (app.tenant_id, set per transaction:
-- set_config(…, true) / SET LOCAL).
-- Fails closed: without an active clinic the setting is NULL (missing) or '' (reset), NULLIF turns both into
-- NULL and the comparison is never true. Reads, UPDATE and DELETE then see no row, silently; an INSERT (or an
-- UPDATE that moves a row to another clinic) breaks WITH CHECK and raises 42501. A value that is not a UUID
-- raises 22P02 on every query: still closed, but loud.
-- Only migrations call it: EXECUTE is revoked from PUBLIC and from the application.
CREATE FUNCTION enable_tenant_isolation(p_table text, p_column text DEFAULT 'tenant_id')
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS
$$
BEGIN
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format(
            'CREATE POLICY tenant_isolation ON public.%I FOR ALL'
                || ' USING (%I = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)'
                || ' WITH CHECK (%I = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            p_table, p_column, p_column);
END;
$$;

REVOKE EXECUTE ON FUNCTION enable_tenant_isolation(text, text) FROM PUBLIC, dentasoft_app;

-- A clinic is the tenant itself: its policy compares `id` instead of `tenant_id`, so a session only sees its
-- own clinic. Creating a clinic therefore requires setting app.tenant_id to the new id (see M01-T05).
CREATE TABLE clinic
(
    id              uuid PRIMARY KEY,
    name            varchar(200) NOT NULL,
    legal_name      varchar(200) NOT NULL,
    tax_id          varchar(20)  NOT NULL,
    timezone        varchar(64)  NOT NULL DEFAULT 'Europe/Madrid',
    locale          varchar(16)  NOT NULL DEFAULT 'es-ES',
    status          varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    keycloak_org_id varchar(64),
    created_at      timestamptz  NOT NULL,
    created_by      uuid,
    updated_at      timestamptz  NOT NULL,
    updated_by      uuid,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT clinic_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT clinic_keycloak_org_id_key UNIQUE (keycloak_org_id)
);

SELECT enable_tenant_isolation('clinic', 'id');
