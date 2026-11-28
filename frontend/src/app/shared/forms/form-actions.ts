import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, startWith, switchMap } from 'rxjs';

import { FailureMessage } from './failure-message';
import { SaveFailure } from './save-failure';

/**
 * The common end of a configuration form (M02-T09): why the last save failed, and the buttons to
 * save and to discard the changes. Both are disabled while nothing changed or while saving; the
 * section saves on (save) and puts back the last saved values on (discard). A section the user
 * only sees (access VIEW) does not show it.
 */
@Component({
  selector: 'app-form-actions',
  imports: [FailureMessage, MatButtonModule, TranslocoPipe],
  template: `
    <app-failure-message [failure]="failure()" />
    <div class="form-actions__buttons">
      <button
        mat-stroked-button
        type="button"
        class="form-actions__discard"
        [disabled]="!dirty() || saving()"
        (click)="discard.emit()"
      >
        {{ 'forms.discard' | transloco }}
      </button>
      <button
        mat-flat-button
        type="button"
        class="form-actions__save"
        [disabled]="!dirty() || saving()"
        (click)="save.emit()"
      >
        {{ (saving() ? 'forms.saving' : 'forms.save') | transloco }}
      </button>
    </div>
  `,
  styles: `
    .form-actions__buttons {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormActions {
  readonly form = input.required<AbstractControl>();
  readonly saving = input(false);
  readonly failure = input<SaveFailure | null>(null);

  readonly save = output<void>();
  readonly discard = output<void>();

  protected readonly dirty = toSignal(
    toObservable(this.form).pipe(
      switchMap((form) =>
        form.events.pipe(
          startWith(null),
          map(() => form.dirty),
        ),
      ),
    ),
    { initialValue: false },
  );
}
