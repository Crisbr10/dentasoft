import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl } from '@angular/forms';

/**
 * Why saving a form failed, as the form shows it:
 * - validation: the server refused some fields; their messages are on the controls (`server` error);
 * - problem: the server explained it in a ProblemDetail (a version conflict, a repeated name…);
 * - unexpected: anything else (network, 5xx); the form shows a generic message.
 */
export type SaveFailure =
  | { readonly kind: 'validation' }
  | { readonly kind: 'problem'; readonly title: string | null; readonly detail: string | null }
  | { readonly kind: 'unexpected' };

interface InvalidField {
  readonly field?: unknown;
  readonly message?: unknown;
}

interface Problem {
  readonly title?: unknown;
  readonly detail?: unknown;
  readonly errors?: unknown;
}

/**
 * Reads a failed save. A 400 with the backend's `errors` ([{field, message}], never the value
 * typed) puts each message on its control as the `server` error and marks it as touched; editing
 * the control runs its validators again and clears it. Fields the form does not have are not lost:
 * the ProblemDetail is shown instead.
 */
export function toSaveFailure(form: AbstractControl, error: unknown): SaveFailure {
  if (!(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500) {
    return { kind: 'unexpected' };
  }
  const problem = (
    typeof error.error === 'object' && error.error !== null ? error.error : {}
  ) as Problem;
  if (error.status === 400 && Array.isArray(problem.errors) && problem.errors.length > 0) {
    const fields = problem.errors as InvalidField[];
    // Every field gets its message, even after one the form does not have.
    const placed = fields.map((field) => placeOnControl(form, field));
    if (placed.every(Boolean)) {
      return { kind: 'validation' };
    }
  }
  return { kind: 'problem', title: text(problem.title), detail: text(problem.detail) };
}

/** `hours[0].opens` from Spring is `hours.0.opens` for Angular forms. */
function placeOnControl(form: AbstractControl, field: InvalidField): boolean {
  if (typeof field.field !== 'string') {
    return false;
  }
  const control = form.get(field.field.replace(/\[(\d+)\]/g, '.$1'));
  if (!control) {
    return false;
  }
  control.setErrors({ ...control.errors, server: text(field.message) ?? true });
  control.markAsTouched();
  return true;
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}
