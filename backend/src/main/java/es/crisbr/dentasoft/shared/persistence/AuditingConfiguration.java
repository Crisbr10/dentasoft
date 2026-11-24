package es.crisbr.dentasoft.shared.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Fills the audit columns of {@code BaseEntity}: who and when an entity was created and last changed. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditor", dateTimeProviderRef = "auditingTime")
class AuditingConfiguration {

    /**
     * The user behind each change. Empty until users exist: M02 replaces it with the Keycloak user ID ({@code sub})
     * of the authenticated request.
     */
    @Bean
    AuditorAware<UUID> auditor() {
        return Optional::empty;
    }

    /**
     * An {@link Instant}, not the default local date-time: converting a local time back to an instant is ambiguous in
     * the hour that repeats when daylight saving time ends.
     */
    @Bean
    DateTimeProvider auditingTime() {
        return () -> Optional.of(Instant.now());
    }
}
