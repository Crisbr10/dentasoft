package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** 404: the clinic has no pending invitation of an administrator to resend or cancel (M02-T11). */
class FirstAdminInvitationNotFoundException extends ErrorResponseException {

    FirstAdminInvitationNotFoundException() {
        super(HttpStatus.NOT_FOUND, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.first-admin-invitation-not-found.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.first-admin-invitation-not-found.detail";
    }
}
