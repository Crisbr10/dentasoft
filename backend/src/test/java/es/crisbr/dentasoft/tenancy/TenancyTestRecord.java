package es.crisbr.dentasoft.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Test-only entity with clinic data, on the table of {@code db/testmigration/V900__tenancy_test_record.sql}. */
@Entity
@Table(name = "tenancy_test_record")
class TenancyTestRecord extends TenantScopedEntity {

    @Column(name = "label", nullable = false)
    private String label;

    protected TenancyTestRecord() {}

    TenancyTestRecord(String label) {
        this.label = label;
    }

    String getLabel() {
        return label;
    }

    void setLabel(String label) {
        this.label = label;
    }
}
