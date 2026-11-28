-- Lets the application read the audit log of the active clinic (M01-T09, ADR 0012): the clinic administrator
-- consults and exports it from Configuración › Seguridad y auditoría.
--
-- Runs as dentasoft_owner.
--
-- Row Level Security already limits every statement to the active clinic: the tenant_isolation policy of V004 is
-- FOR ALL, so a SELECT sees only the rows whose tenant_id is app.tenant_id, and none without an active clinic.
-- Only SELECT is granted: UPDATE and DELETE stay revoked, and V004's trigger rejects them for every role anyway.
-- The platform role gets nothing: it records what it does to a clinic, but reads no clinic's log.
--
-- Reverting by hand: REVOKE SELECT ON audit_log FROM dentasoft_app;

GRANT SELECT ON audit_log TO dentasoft_app;
