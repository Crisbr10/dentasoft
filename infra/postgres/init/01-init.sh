#!/usr/bin/env bash
# Creates the databases, roles and extensions Dentasoft needs.
#
# The postgres image runs this script ONLY when the data volume is empty (first start).
# If you change it, recreate the volume (see infra/README.md).
#
# Scope: roles, databases, schema ownership and extensions. Tables and table privileges
# belong to the Flyway migrations (M01-T01); the Keycloak realm belongs to M02-T01.
set -euo pipefail

: "${DENTASOFT_DB_OWNER_PASSWORD:?DENTASOFT_DB_OWNER_PASSWORD is required}"
: "${DENTASOFT_DB_APP_PASSWORD:?DENTASOFT_DB_APP_PASSWORD is required}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"

# Passwords travel as psql variables (:'name' quotes them as literals), never concatenated.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  -v owner_password="$DENTASOFT_DB_OWNER_PASSWORD" \
  -v app_password="$DENTASOFT_DB_APP_PASSWORD" \
  -v keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
-- Roles. None of them is superuser, creates databases or roles, or bypasses RLS.
CREATE ROLE dentasoft_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'owner_password';
CREATE ROLE dentasoft_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'app_password';
CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'keycloak_password';

-- Databases. Each role connects only to its own database.
CREATE DATABASE dentasoft OWNER dentasoft_owner;
CREATE DATABASE keycloak OWNER keycloak;

REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE dentasoft FROM PUBLIC;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE dentasoft TO dentasoft_app;

\connect dentasoft

-- pgvector is not a trusted extension, so the superuser creates it here.
CREATE EXTENSION IF NOT EXISTS vector;

-- Only the owner creates objects in `public`; the application can use it but not create in it.
ALTER SCHEMA public OWNER TO dentasoft_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO dentasoft_app;
SQL
