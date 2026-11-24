package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The list was asked for a page number beyond {@link ClinicAdministration#MAX_PAGE_NUMBER}: 400. */
class PageOutOfRangeException extends ErrorResponseException {

    PageOutOfRangeException() {
        super(HttpStatus.BAD_REQUEST, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.page-out-of-range.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.page-out-of-range.detail";
    }
}
