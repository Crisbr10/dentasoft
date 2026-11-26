package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** No one changes the role of, or deactivates, their own membership: 409. Another administrator does it. */
public class OwnMembershipException extends ErrorResponseException {

    OwnMembershipException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.own-membership.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.own-membership.detail";
    }
}
