-- Test-only migration: it lives on the test classpath (spring.flyway.locations in WithTestProperties) and never
-- reaches a real database. A table with clinic data for the tests of TenantScopedEntity (M01-T02), created exactly
-- as docs/plan/M01-clinicas-multitenant.md describes, so RowLevelSecurityGuardTests checks it as well.
CREATE TABLE tenancy_test_record
(
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES clinic (id),
    label      varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL,
    created_by uuid,
    updated_at timestamptz  NOT NULL,
    updated_by uuid,
    version    bigint       NOT NULL DEFAULT 0
);

CREATE INDEX tenancy_test_record_tenant_id_idx ON tenancy_test_record (tenant_id);

SELECT enable_tenant_isolation('tenancy_test_record');
