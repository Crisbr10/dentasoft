package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * A request to the clinic API does not name its active clinic properly: no {@code X-Clinic-Id} header, more than one,
 * or a value that is not a UUID. 400, the same for all of them.
 */
class InvalidClinicHeaderException extends ErrorResponseException {

    InvalidClinicHeaderException() {
        super(HttpStatus.BAD_REQUEST, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.clinic-header.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.clinic-header.detail";
    }
}
