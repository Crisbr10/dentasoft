package es.crisbr.dentasoft.tenancy.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Settings of the active clinic: Hibernate and Row Level Security only ever show its own row. */
public interface ClinicSettingsRepository extends JpaRepository<ClinicSettings, UUID> {}
