import { FormGroup } from '@angular/forms';

/**
 * After a failed submit, moves the focus to the first invalid field of the form, in the order of
 * its controls, so keyboard and screen reader users land on what to fix. The field is found by
 * its `formControlName` inside `host`.
 */
export function focusFirstInvalid(form: FormGroup, host: HTMLElement): void {
  const name = Object.keys(form.controls).find((key) => form.controls[key].invalid);
  if (name) {
    host.querySelector<HTMLElement>(`[formcontrolname="${name}"]`)?.focus();
  }
}
