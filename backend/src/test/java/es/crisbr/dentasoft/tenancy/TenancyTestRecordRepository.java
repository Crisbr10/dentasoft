package es.crisbr.dentasoft.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Test-only repository. Its derived and {@code @Query} methods make Spring Data open sessions at startup, without an
 * active clinic, so the application starting at all proves that a session without a clinic does not fail.
 */
interface TenancyTestRecordRepository extends JpaRepository<TenancyTestRecord, UUID> {

    List<TenancyTestRecord> findByLabel(String label);

    @Query("select r from TenancyTestRecord r where r.label like :prefix%")
    List<TenancyTestRecord> findByLabelPrefix(String prefix);

    @Modifying
    @Query("update TenancyTestRecord r set r.label = :label")
    int relabelAll(String label);
}
