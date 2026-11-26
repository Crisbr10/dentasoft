package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The invited person already has an active membership in the active clinic: 409. Their role is changed instead. */
public class AlreadyMemberException extends ErrorResponseException {

    AlreadyMemberException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.already-member.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.already-member.detail";
    }
}
