package es.crisbr.dentasoft.platform.persistence;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * A clinic as the platform administration sees it, through {@code dentasoft_platform} (ADR 0011). Not a {@code
 * TenantScopedEntity}: the platform unit works across clinics, and the clinic is the tenant itself.
 *
 * <p>The platform creates clinics, changes their status and corrects their fiscal data ({@code legal_name} and
 * {@code tax_id}, M01-T11). Every other column is not updatable, and {@code @DynamicUpdate} keeps them out of the
 * UPDATE, which only has privileges on those columns and the audit and version columns (V002, V006).
 */
@Entity(name = "PlatformClinic")
@Table(name = "clinic")
@DynamicUpdate
public class PlatformClinic extends BaseEntity {

    public static final String DEFAULT_TIMEZONE = "Europe/Madrid";
    public static final String DEFAULT_LOCALE = "es-ES";

    @Column(name = "name", nullable = false, updatable = false, length = 200)
    private String name;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "tax_id", nullable = false, length = 20)
    private String taxId;

    @Column(name = "timezone", nullable = false, updatable = false, length = 64)
    private String timezone;

    @Column(name = "locale", nullable = false, updatable = false, length = 16)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClinicStatus status = ClinicStatus.ACTIVE;

    /** Set by the identity module (M02); the platform never writes it. */
    @Column(name = "keycloak_org_id", insertable = false, updatable = false, length = 64)
    private @Nullable String keycloakOrgId;

    protected PlatformClinic() {
        // For Hibernate.
        name = legalName = taxId = timezone = locale = "";
    }

    public PlatformClinic(String name, String legalName, String taxId, String timezone, String locale) {
        this.name = name;
        this.legalName = legalName;
        this.taxId = taxId;
        this.timezone = timezone;
        this.locale = locale;
    }

    public String getName() {
        return name;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTaxId() {
        return taxId;
    }

    /** Whether the clinic already has this legal name and tax identifier. */
    public boolean hasFiscalData(String legalName, String taxId) {
        return this.legalName.equals(legalName) && this.taxId.equals(taxId);
    }

    /** Corrects the legal name and the tax identifier, already validated and normalized by the web layer. */
    public void correctFiscalData(String legalName, String taxId) {
        this.legalName = legalName;
        this.taxId = taxId;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getLocale() {
        return locale;
    }

    public ClinicStatus getStatus() {
        return status;
    }

    public void setStatus(ClinicStatus status) {
        this.status = status;
    }

    public @Nullable String getKeycloakOrgId() {
        return keycloakOrgId;
    }
}
