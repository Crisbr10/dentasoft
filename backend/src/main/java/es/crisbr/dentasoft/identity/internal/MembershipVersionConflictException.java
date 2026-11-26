package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The membership changed since the client read it: 409. The client reloads it and tries again. */
public class MembershipVersionConflictException extends ErrorResponseException {

    MembershipVersionConflictException() {
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
