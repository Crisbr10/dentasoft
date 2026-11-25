package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * The correction was made on a version of the clinic that is no longer the current one, because someone else changed
 * it in between: 409, with the same message as the clinic's own version conflicts. The client reloads the clinic and
 * tries again.
 */
class ClinicVersionConflictException extends ErrorResponseException {

    ClinicVersionConflictException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.version-conflict.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.version-conflict.detail";
    }
}
