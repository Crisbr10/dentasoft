package es.crisbr.dentasoft.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** The request body is larger than {@code dentasoft.http.max-request-body-size}: 413. */
class RequestBodyTooLargeException extends ErrorResponseException {

    RequestBodyTooLargeException() {
        super(HttpStatus.CONTENT_TOO_LARGE, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.request-too-large.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.request-too-large.detail";
    }
}
