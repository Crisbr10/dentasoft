package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * 409: the clinic already has an active administrator, accepted or pending, so the platform cannot invite another
 * (M02-T11). A pending invitation is resent or cancelled instead.
 */
class FirstAdminConflictException extends ErrorResponseException {

    FirstAdminConflictException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.first-admin-conflict.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.first-admin-conflict.detail";
    }
}
