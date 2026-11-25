package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditedEntity;
import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The active clinic, as the clinic itself sees and edits it ({@code clinic}, V001). The platform creates it and changes
 * its status (M01-T05); the clinic only renames itself and changes its time zone (M01-T06).
 *
 * <p>It is not a {@link es.crisbr.dentasoft.tenancy.TenantScopedEntity}: the row is the clinic itself, so its policy
 * compares {@code id} with the active clinic, and Hibernate adds no filter. Only Row Level Security keeps a clinic from
 * reading another one, which {@code ClinicIsolationTests} checks.
 *
 * <p>The application may only update {@code name}, {@code timezone} and the audit and version columns (V003). Hibernate
 * writes every updatable column it maps, so the others are mapped read-only ({@code legal_name}, {@code tax_id},
 * {@code locale}) or not at all ({@code status}, {@code keycloak_org_id}). The application cannot insert clinics.
 */
@Entity
@AuditedEntity(AuditEntityType.CLINIC)
@Table(name = "clinic")
public class Clinic extends BaseEntity {

    /** Time zone of a clinic created without one, as in the column's DEFAULT. */
    public static final String DEFAULT_TIMEZONE = "Europe/Madrid";

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "legal_name", nullable = false, length = 200, insertable = false, updatable = false)
    private String legalName;

    @Column(name = "tax_id", nullable = false, length = 20, insertable = false, updatable = false)
    private String taxId;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "locale", nullable = false, length = 16, insertable = false, updatable = false)
    private String locale;

    protected Clinic() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLocale() {
        return locale;
    }
}
