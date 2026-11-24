package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * The change was made on a version of the data that is no longer the current one, because someone else changed it in
 * between: 409. The client reloads the data and tries again.
 */
public class VersionConflictException extends ErrorResponseException {

    VersionConflictException() {
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
