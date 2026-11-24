package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A treatment room (gabinete) of the active clinic ({@code location}, V003), named after FHIR {@code Location}.
 *
 * <p>Never deleted: a room that is no longer used is made {@link LocationStatus#INACTIVE}, so what took place in it
 * keeps pointing at it. Two active rooms of a clinic never share a name, whatever the case.
 */
@Entity
@Table(name = "location")
public class Location extends TenantScopedEntity {

    /** Name of the unique index that keeps active names apart, as in V003. */
    public static final String ACTIVE_NAME_KEY = "location_tenant_id_active_name_key";

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "color", length = 7)
    private @Nullable String color;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LocationStatus status = LocationStatus.ACTIVE;

    protected Location() {}

    public Location(String name, @Nullable String color) {
        this.name = name;
        this.color = color;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public @Nullable String getColor() {
        return color;
    }

    public void setColor(@Nullable String color) {
        this.color = color;
    }

    public LocationStatus getStatus() {
        return status;
    }

    public void setStatus(LocationStatus status) {
        this.status = status;
    }
}
