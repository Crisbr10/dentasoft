package es.crisbr.dentasoft.shared;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** The limits every paginated list of the API applies to the page it is asked for. */
public final class PageLimits {

    /** Larger pages are cut to this size. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Far beyond any real list, and low enough that the offset always fits in an int. */
    public static final int MAX_PAGE_NUMBER = 100_000;

    private PageLimits() {}

    /**
     * {@code pageable} within the limits: its size cut to {@link #MAX_PAGE_SIZE}.
     *
     * @param sortable the properties the list may be sorted by
     * @throws UnsupportedSortException if it is sorted by any other property
     * @throws PageOutOfRangeException if its page number is beyond {@link #MAX_PAGE_NUMBER}
     */
    public static Pageable bounded(Pageable pageable, Set<String> sortable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!sortable.contains(order.getProperty())) {
                throw new UnsupportedSortException();
            }
        }
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new PageOutOfRangeException();
        }
        return pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
    }
}
