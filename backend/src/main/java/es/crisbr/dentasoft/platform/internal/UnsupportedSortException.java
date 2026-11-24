package es.crisbr.dentasoft.platform.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The list was asked to be sorted by a property it cannot be sorted by: 400. */
class UnsupportedSortException extends ErrorResponseException {

    UnsupportedSortException() {
        super(HttpStatus.BAD_REQUEST, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.unsupported-sort.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.unsupported-sort.detail";
    }
}
