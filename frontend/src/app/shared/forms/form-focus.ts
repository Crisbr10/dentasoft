import { FormGroup } from '@angular/forms';

/** What can take the focus inside a field that cannot itself, such as a radio group. */
const FOCUSABLE = 'input, select, textarea, button, [tabindex]:not([tabindex="-1"])';

/**
 * After a failed submit, moves the focus to the first invalid field of the form, in the order of
 * its controls, so keyboard and screen reader users land on what to fix. The field is found by
 * its `formControlName` inside `host`; when it cannot take the focus (a `mat-radio-group`), its
 * first focusable element does.
 */
export function focusFirstInvalid(form: FormGroup, host: HTMLElement): void {
  const name = Object.keys(form.controls).find((key) => form.controls[key].invalid);
  if (!name) {
    return;
  }
  const field = host.querySelector<HTMLElement>(`[formcontrolname="${name}"]`);
  const target = field?.matches(FOCUSABLE) ? field : field?.querySelector<HTMLElement>(FOCUSABLE);
  target?.focus();
}
