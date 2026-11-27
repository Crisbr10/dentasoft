package es.crisbr.dentasoft.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * The practitioner chosen for something new does not exist in the active clinic, or is no longer active: 409. Says
 * which of the two neither to the client nor to whoever catches it, so another clinic's ids reveal nothing.
 */
public class PractitionerUnavailableException extends ErrorResponseException {

    public PractitionerUnavailableException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.practitioner-unavailable.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.practitioner-unavailable.detail";
    }
}
