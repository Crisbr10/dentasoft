import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, startWith, switchMap } from 'rxjs';

import { SaveFailure } from './save-failure';

/**
 * The common end of a configuration form (M02-T09): why the last save failed, and the buttons to
 * save and to discard the changes. Both are disabled while nothing changed or while saving; the
 * section saves on (save) and puts back the last saved values on (discard). A section the user
 * only sees (access VIEW) does not show it.
 */
@Component({
  selector: 'app-form-actions',
  imports: [MatButtonModule, TranslocoPipe],
  template: `
    @if (failure(); as failure) {
      <div class="form-actions__failure" role="alert">
        @switch (failure.kind) {
          @case ('validation') {
            <p>{{ 'forms.invalid' | transloco }}</p>
          }
          @case ('problem') {
            @if (failure.title) {
              <p class="form-actions__title">{{ failure.title }}</p>
            }
            <p>{{ failure.detail ?? ('forms.unexpected' | transloco) }}</p>
          }
          @default {
            <p>{{ 'forms.unexpected' | transloco }}</p>
          }
        }
      </div>
    }
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

    .form-actions__title {
      font-weight: 500;
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
