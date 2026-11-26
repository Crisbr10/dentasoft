package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The active clinic has no practitioner record with that id: 404. Another clinic's records are not found either. */
public class PractitionerNotFoundException extends ErrorResponseException {

    PractitionerNotFoundException() {
        super(HttpStatus.NOT_FOUND, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.practitioner-not-found.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.practitioner-not-found.detail";
    }
}
