#!/usr/bin/env bash
# Creates dentasoft_platform, the role of the platform administration module (M01-T05, ADR 0011).
#
# The postgres image runs this script after 01-init.sh when the data volume is empty (first start). It is
# idempotent, so it can also be run by hand on an existing database, as the superuser inside the container:
#   docker compose -f compose.yml -f compose.local.yml exec postgres bash /docker-entrypoint-initdb.d/02-platform-role.sh
# Other environments: docs/operaciones/rol-de-plataforma.md.
#
# Scope: the role, its login and what it needs to reach the schema. Its table and column privileges and its
# policies belong to the Flyway migrations (V002).
set -euo pipefail

: "${DENTASOFT_DB_PLATFORM_PASSWORD:?DENTASOFT_DB_PLATFORM_PASSWORD is required}"

# The password travels as a psql variable and %L quotes it as a literal, never concatenated.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  -v platform_password="$DENTASOFT_DB_PLATFORM_PASSWORD" <<'SQL'
-- Not superuser, does not bypass RLS and is a member of no role: it sees all clinics only through the policies
-- written for it, and dentasoft_app is never granted this role, so the application role cannot SET ROLE to it.
SELECT format('CREATE ROLE dentasoft_platform LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
                  || ' NOBYPASSRLS NOINHERIT PASSWORD %L', :'platform_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dentasoft_platform')
\gexec

GRANT CONNECT ON DATABASE dentasoft TO dentasoft_platform;

\connect dentasoft

GRANT USAGE ON SCHEMA public TO dentasoft_platform;
SQL
