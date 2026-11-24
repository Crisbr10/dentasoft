package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Another active treatment room of the clinic already has that name, whatever the case: 409. */
public class LocationNameTakenException extends ErrorResponseException {

    LocationNameTakenException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.location-name-taken.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.location-name-taken.detail";
    }
}
