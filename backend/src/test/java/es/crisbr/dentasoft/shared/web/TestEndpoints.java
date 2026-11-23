package es.crisbr.dentasoft.shared.web;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints used only by the tests of the cross-cutting filters. All the data is invented. */
@RestController
class TestEndpoints {

    /** Invented personal data that must never reach the logs. */
    static final String PATIENT_NAME = "Ana Inventada Prueba";

    static final String PATIENT_PHONE = "600111222";

    private static final Logger log = LoggerFactory.getLogger(TestEndpoints.class);

    @GetMapping("/api/v1/test/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/v1/test/echo")
    Map<String, Integer> echo(@RequestBody Map<String, Object> body) {
        return Map.of("fields", body.size());
    }

    @GetMapping("/api/v1/test/failure")
    void failure() {
        log.info("Loading patient {}", UUID.fromString("0190a6e8-0000-7000-8000-000000000001"));
        throw new IllegalStateException(
                "Duplicate patient " + PATIENT_NAME + ", phone " + PATIENT_PHONE,
                new IllegalArgumentException("Rejected value " + PATIENT_PHONE));
    }
}
