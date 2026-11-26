package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The person has already set up their account (email verified, password and one-time code), so there is no invitation to send again: 409. */
public class InvitationNotPendingException extends ErrorResponseException {

    InvitationNotPendingException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.invitation-not-pending.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.invitation-not-pending.detail";
    }
}
