package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** No clinic has the requested id: 404. */
class ClinicNotFoundException extends ErrorResponseException {

    ClinicNotFoundException() {
        super(HttpStatus.NOT_FOUND, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.clinic-not-found.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.clinic-not-found.detail";
    }
}
