package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The active clinic has no treatment room with the requested id, whether it exists in another clinic or not: 404. */
public class LocationNotFoundException extends ErrorResponseException {

    LocationNotFoundException() {
        super(HttpStatus.NOT_FOUND, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.location-not-found.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.location-not-found.detail";
    }
}
