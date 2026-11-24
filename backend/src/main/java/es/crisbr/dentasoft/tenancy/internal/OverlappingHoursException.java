package es.crisbr.dentasoft.tenancy.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * Two opening slots of the same day overlap, or one closes before it opens: 400. Slots that only touch (09:00–14:00 and
 * 14:00–18:00) do not overlap.
 */
public class OverlappingHoursException extends ErrorResponseException {

    OverlappingHoursException() {
        super(HttpStatus.BAD_REQUEST, null);
    }

    @Override
    public String getTitleMessageCode() {
        return "problemDetail.overlapping-hours.title";
    }

    @Override
    public String getDetailMessageCode() {
        return "problemDetail.overlapping-hours.detail";
    }
}
