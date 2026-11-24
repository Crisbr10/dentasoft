package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The clinic cannot move to the requested status from the one it has (suspending a suspended clinic…): 409. */
class ClinicStatusConflictException extends ErrorResponseException {

    private final ClinicStatus requested;

    ClinicStatusConflictException(ClinicStatus requested) {
        super(HttpStatus.CONFLICT, null);
        this.requested = requested;
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.clinic-status-conflict.title";
    }

    @Override
    public String getDetailMessageCode() {
        return requested == ClinicStatus.SUSPENDED
                ? "problemDetail.clinic-status-conflict.already-suspended"
                : "problemDetail.clinic-status-conflict.already-active";
    }
}
