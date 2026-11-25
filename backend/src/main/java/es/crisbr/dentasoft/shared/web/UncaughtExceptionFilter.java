package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.Logging;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Answers an exception that escapes the other filters as {@link GlobalExceptionHandler} answers one from a controller:
 * logged without its messages and returned as a {@code ProblemDetail}. Otherwise it would reach Tomcat, which logs it
 * whole, messages included, and those may carry personal data (#126).
 *
 * <p>It runs inside {@link RequestIdFilter}, so the log line keeps the request ID, and around every other filter, whose
 * headers the error keeps. The HTTP observation, outside it, still tags the request with the failure's class:
 * {@code GlobalExceptionHandler} leaves a 500's exception in the request for it. Three cases go on to Tomcat as before:
 *
 * <ul>
 *   <li>A client that went away: Tomcat logs it at debug, and its message says only that the connection closed.
 *   <li>A response already committed, which can no longer be answered: logged here, then rethrown without its messages,
 *       so that Tomcat closes the connection instead of ending a truncated response as if it were whole.
 *   <li>An error of the virtual machine (out of memory, for instance), whose message carries no data.
 * </ul>
 */
class UncaughtExceptionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UncaughtExceptionFilter.class);

    private final HandlerExceptionResolver exceptionResolver;

    UncaughtExceptionFilter(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable failure) {
            if (DisconnectedClientHelper.isClientDisconnectedException(failure)) {
                throw failure;
            }
            if (response.isCommitted()) {
                logUnhandled(failure);
                throw Logging.redacted(failure);
            }
            ModelAndView handled = failure instanceof Exception exception
                    ? exceptionResolver.resolveException(request, response, null, exception)
                    : null;
            if (handled == null) {
                logUnhandled(failure);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static void logUnhandled(Throwable failure) {
        log.error("Unhandled exception {}", failure.getClass().getName(), Logging.redacted(failure));
    }

    /** The error page has filters too, and one of them may fail while it is shown. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
