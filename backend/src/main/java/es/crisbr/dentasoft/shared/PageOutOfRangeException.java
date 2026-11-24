package es.crisbr.dentasoft.shared;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The list was asked for a page number beyond {@link PageLimits#MAX_PAGE_NUMBER}: 400. */
public class PageOutOfRangeException extends ErrorResponseException {

    public PageOutOfRangeException() {
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
