package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The active clinic has no membership with that id: 404. Another clinic's memberships are not found either. */
public class MembershipNotFoundException extends ErrorResponseException {

    MembershipNotFoundException() {
        super(HttpStatus.NOT_FOUND, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.membership-not-found.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.membership-not-found.detail";
    }
}
