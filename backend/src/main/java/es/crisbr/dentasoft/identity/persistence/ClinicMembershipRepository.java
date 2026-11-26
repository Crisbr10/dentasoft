package es.crisbr.dentasoft.identity.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The memberships of the active clinic. Hibernate filters every query by the active clinic, so a user's memberships
 * in other clinics are read with SQL instead ({@code identity.internal.UserAccounts}).
 */
public interface ClinicMembershipRepository extends JpaRepository<ClinicMembership, UUID> {}
