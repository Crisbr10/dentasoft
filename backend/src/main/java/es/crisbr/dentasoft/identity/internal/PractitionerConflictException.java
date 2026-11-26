package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** A change of a practitioner record that the state of the clinic does not allow: 409, with one message per reason. */
public class PractitionerConflictException extends ErrorResponseException {

    /** Why the change was refused; each one has its own {@code problemDetail.<code>} messages. */
    enum Reason {
        /** Another active record of the clinic has the same licence number and province. */
        LICENSE_TAKEN("practitioner-license-taken"),
        /** The user already has a record in the clinic, active or not. */
        USER_TAKEN("practitioner-user-taken"),
        /** The user has no active membership of the clinic as {@code ODONTOLOGO} or {@code HIGIENISTA}. */
        USER_NOT_ELIGIBLE("practitioner-user-not-eligible"),
        /** The clinical area does not suit the user's role. */
        SPECIALTY_MISMATCH("practitioner-specialty-mismatch"),
        /** The record already belongs to a user, and would change or lose it. */
        USER_FIXED("practitioner-user-fixed"),
        /** The record changed since the client read it. */
        VERSION("version-conflict");

        private final String code;

        Reason(String code) {
            this.code = code;
        }
    }

    private final Reason reason;

    PractitionerConflictException(Reason reason) {
        super(HttpStatus.CONFLICT, null);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail." + reason.code + ".title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail." + reason.code + ".detail";
    }
}
