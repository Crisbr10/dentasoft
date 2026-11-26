package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The person has already accepted the invitation, so there is none to send again: 409. */
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
