package es.crisbr.dentasoft.identity.persistence;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditedEntity;
import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a person does as a practitioner in the active clinic ({@code practitioner_role}, V012), named after FHIR
 * {@code PractitionerRole}: licence number, clinical area and calendar colour depend on the clinic.
 *
 * <p>Linked to the person's user when they have an account, and then to their membership of the clinic; without a
 * user for someone who never signs in. The user is set once and never changes (V012), so past appointments never show
 * another person. Never deleted: a practitioner who stops working at the clinic is made
 * {@link PractitionerRoleStatus#INACTIVE}, and what was given with them keeps pointing at the record.
 */
@Entity
@AuditedEntity(AuditEntityType.PRACTITIONER_ROLE)
@Table(name = "practitioner_role")
public class PractitionerRole extends TenantScopedEntity {

    /** Name of the unique index that keeps the licence numbers of active records apart, as in V012. */
    public static final String ACTIVE_LICENSE_KEY = "practitioner_role_tenant_id_active_license_key";

    /** Name of the unique constraint that gives a user at most one record per clinic, as in V012. */
    public static final String USER_KEY = "practitioner_role_tenant_id_user_id_key";

    @Column(name = "user_id")
    private @Nullable UUID userId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 30)
    private PractitionerSpecialty specialty;

    @Column(name = "license_province", length = 2)
    private @Nullable String licenseProvince;

    @Column(name = "license_number", length = 12)
    private @Nullable String licenseNumber;

    @Column(name = "agenda_color", nullable = false, length = 7)
    private String agendaColor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PractitionerRoleStatus status = PractitionerRoleStatus.ACTIVE;

    protected PractitionerRole() {}

    public PractitionerRole(
            @Nullable UUID userId, String displayName, PractitionerSpecialty specialty, String agendaColor) {
        this.userId = userId;
        this.displayName = displayName;
        this.specialty = specialty;
        this.agendaColor = agendaColor;
    }

    public @Nullable UUID getUserId() {
        return userId;
    }

    /** Links the record to the user of a person who had no account. A linked record never changes user (V012). */
    public void linkUser(UUID userId) {
        if (this.userId != null && !this.userId.equals(userId)) {
            throw new IllegalStateException("A practitioner record never changes user");
        }
        this.userId = userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PractitionerSpecialty getSpecialty() {
        return specialty;
    }

    public void setSpecialty(PractitionerSpecialty specialty) {
        this.specialty = specialty;
    }

    public @Nullable String getLicenseProvince() {
        return licenseProvince;
    }

    public @Nullable String getLicenseNumber() {
        return licenseNumber;
    }

    /** Both or neither: a licence number belongs to the college of one province (V012). */
    public void setLicense(@Nullable String province, @Nullable String number) {
        this.licenseProvince = province;
        this.licenseNumber = number;
    }

    public String getAgendaColor() {
        return agendaColor;
    }

    public void setAgendaColor(String agendaColor) {
        this.agendaColor = agendaColor;
    }

    public PractitionerRoleStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == PractitionerRoleStatus.ACTIVE;
    }

    public void setStatus(PractitionerRoleStatus status) {
        this.status = status;
    }
}
