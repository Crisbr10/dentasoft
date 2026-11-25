package es.crisbr.dentasoft.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.stream.StreamSupport;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The change listener is in place once the application has started: without it, audited changes would go unrecorded
 * without a single error.
 */
@IntegrationTest
class AuditListenerRegistrationTests {

    @Autowired
    private EntityManagerFactory mainUnit;

    @Test
    void theMainUnitRecordsInsertsUpdatesAndDeletesOnce() {
        EventListenerRegistry registry = mainUnit.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(EventListenerRegistry.class);

        for (EventType<?> type : List.of(EventType.POST_INSERT, EventType.POST_UPDATE, EventType.POST_DELETE)) {
            long listeners = StreamSupport.stream(
                            registry.getEventListenerGroup(type).listeners().spliterator(), false)
                    .filter(AuditChangeListener.class::isInstance)
                    .count();
            assertThat(listeners).as(type.eventName()).isEqualTo(1);
        }
    }
}
