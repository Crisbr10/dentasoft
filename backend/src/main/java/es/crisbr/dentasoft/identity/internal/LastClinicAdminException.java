package es.crisbr.dentasoft.identity.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The change would leave the active clinic without an active ADMIN_CLINICA: 409. */
public class LastClinicAdminException extends ErrorResponseException {

    LastClinicAdminException() {
        super(HttpStatus.CONFLICT, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.last-clinic-admin.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.last-clinic-admin.detail";
    }
}
