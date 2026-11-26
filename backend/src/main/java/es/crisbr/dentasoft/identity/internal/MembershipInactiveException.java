package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The membership is inactive: its role cannot change and no invitation is sent for it (409). Inviting the person again reactivates it. */
public class MembershipInactiveException extends ErrorResponseException {

    MembershipInactiveException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.membership-inactive.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.membership-inactive.detail";
    }
}
