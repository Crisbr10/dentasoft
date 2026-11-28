import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, ValidationErrors } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, startWith, switchMap } from 'rxjs';

/**
 * The message of a field's first error, inside its `<mat-error>`: the server's own text first
 * (`server`, see `toSaveFailure`), then required, maxlength, email and taxId. Material shows the
 * `<mat-error>` once the field is touched and links it to the input with aria-describedby.
 */
@Component({
  selector: 'app-control-error',
  imports: [TranslocoPipe],
  template: `
    @if (errors(); as errors) {
      @if (errors['server']) {
        {{ serverText(errors['server']) ?? ('forms.invalid' | transloco) }}
      } @else if (errors['required']) {
        {{ 'platform.errors.required' | transloco }}
      } @else if (errors['maxlength']) {
        {{ 'platform.errors.maxlength' | transloco: { max: errors['maxlength'].requiredLength } }}
      } @else if (errors['email']) {
        {{ 'platform.errors.email' | transloco }}
      } @else if (errors['taxId']) {
        {{ 'platform.errors.taxId' | transloco }}
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ControlError {
  readonly control = input.required<AbstractControl>();

  protected readonly errors = toSignal(
    toObservable(this.control).pipe(
      switchMap((control) =>
        control.events.pipe(
          startWith(null),
          map((): ValidationErrors | null => control.errors),
        ),
      ),
    ),
    { initialValue: null },
  );

  protected serverText(value: unknown): string | null {
    return typeof value === 'string' ? value : null;
  }
}
