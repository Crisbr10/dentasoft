-- M01-T11: the clinic cannot change its legal_name nor its tax_id (V003). When they have to be corrected, the platform
-- does it, and nothing else of the clinic: no other column is added to what V002 lets it update. The platform_update
-- policy of V002 already covers these rows.
--
-- To undo it by hand: REVOKE UPDATE (legal_name, tax_id) ON clinic FROM dentasoft_platform;
GRANT UPDATE (legal_name, tax_id) ON clinic TO dentasoft_platform;
