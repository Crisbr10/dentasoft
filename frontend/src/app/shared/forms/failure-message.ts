import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { SaveFailure } from './save-failure';

/**
 * Why a save or an action failed (see `SaveFailure`), announced as an alert. The backend's
 * ProblemDetail is already in the user's language; anything else gets a generic message.
 */
@Component({
  selector: 'app-failure-message',
  imports: [TranslocoPipe],
  template: `
    @if (failure(); as failure) {
      <div class="failure-message" role="alert">
        @switch (failure.kind) {
          @case ('validation') {
            <p>{{ 'forms.invalid' | transloco }}</p>
          }
          @case ('problem') {
            @if (failure.title) {
              <p class="failure-message__title">{{ failure.title }}</p>
            }
            <p>{{ failure.detail ?? ('forms.unexpected' | transloco) }}</p>
          }
          @default {
            <p>{{ 'forms.unexpected' | transloco }}</p>
          }
        }
      </div>
    }
  `,
  styles: `
    .failure-message__title {
      font-weight: 500;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FailureMessage {
  readonly failure = input<SaveFailure | null>(null);
}
