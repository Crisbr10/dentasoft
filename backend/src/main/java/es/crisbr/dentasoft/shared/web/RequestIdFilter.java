package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.RequestId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, puts it in the logging MDC ({@value #MDC_KEY}) and returns it in the
 * {@value #HEADER} response header.
 *
 * <p>An incoming {@value #HEADER} is kept only if it is 1 to {@value #MAX_LENGTH} characters of
 * {@code [A-Za-z0-9._-]}; anything else is replaced by a new ID and never echoed, so a client cannot inject
 * text into the logs or the response headers.
 */
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = RequestId.MDC_KEY;
    static final int MAX_LENGTH = 64;

    /** Keeps the ID for the error dispatch of the same request, which runs this filter again. */
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getAttribute(ATTRIBUTE) instanceof String previous
                ? previous
                : acceptOrGenerate(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Errors dispatched to the error page keep the ID of the original request, generated or not. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    static String acceptOrGenerate(@Nullable String candidate) {
        return candidate != null && VALID_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
