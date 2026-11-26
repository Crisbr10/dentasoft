package es.crisbr.dentasoft.identity;

import es.crisbr.dentasoft.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints under the clinic API: what a request sees once its active clinic has been checked. */
@RestController
class ActiveClinicTestEndpoints {

    static final String CONTEXT = "/api/v1/clinic/test/context";
    static final String DENTISTS_ONLY = "/api/v1/clinic/test/dentists-only";
    static final String SWITCH = "/api/v1/clinic/test/switch";

    /** Whether the block of the last {@link #SWITCH} ran with the other clinic. */
    static final AtomicBoolean SWITCHED = new AtomicBoolean();

    private final DentistsOnly dentistsOnly;

    ActiveClinicTestEndpoints(DentistsOnly dentistsOnly) {
        this.dentistsOnly = dentistsOnly;
    }

    /** The active clinic, the user's authorities and whether a transaction is still open when the handler runs. */
    @GetMapping(CONTEXT)
    Map<String, Object> context() {
        List<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        return Map.of(
                "clinic", TenantContext.current().toString(),
                "authorities", authorities,
                "transaction", TransactionSynchronizationManager.isActualTransactionActive());
    }

    @GetMapping(DENTISTS_ONLY)
    String dentistsOnly() {
        return dentistsOnly.greet();
    }

    /** Tries to act as another clinic in the middle of the request. */
    @GetMapping(SWITCH)
    String switchTo(@RequestParam UUID to) {
        TenantContext.runAs(to, () -> SWITCHED.set(true));
        return "switched";
    }

    /** A service that only dentists of the active clinic may call. */
    @Service
    public static class DentistsOnly {

        @PreAuthorize("hasRole('ODONTOLOGO')")
        public String greet() {
            return "hola";
        }
    }
}
