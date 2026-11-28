import { HttpErrorResponse } from '@angular/common/http';

/**
 * The generated client reads the answers of endpoints with no body (202, 204) as text, so their
 * ProblemDetail arrives as a JSON string. This turns it into the object `toSaveFailure` and
 * `toActionFailure` read; any other error is returned as it is.
 */
export function withParsedProblem(error: unknown): unknown {
  if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'string') {
    return error;
  }
  try {
    const body: unknown = JSON.parse(error.error);
    return new HttpErrorResponse({
      error: body,
      headers: error.headers,
      status: error.status,
      statusText: error.statusText,
      url: error.url ?? undefined,
    });
  } catch {
    return error;
  }
}
