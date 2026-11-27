import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * What the backend says about a stored secret: whether there is one and who changed it last.
 * Never the value, nor any part of it (not even its last characters): a few characters of a
 * password or a token already help an attacker (docs/plan/configuracion.md).
 */
export interface SecretStatus {
  readonly configured: boolean;
  /** ISO 8601 instant of the last change. */
  readonly changedAt: string | null;
  /** Name of who changed it, as the backend gives it. */
  readonly changedBy: string | null;
}

/**
 * A write-only secret (M02-T09): shows only «configurado» or «sin configurar» and the last change;
 * to change it, the user types a new one. Its form value is the new secret, or null to keep the
 * stored one. It is never filled with the stored secret, which the browser never receives.
 */
@Component({
  selector: 'app-secret-field',
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatInputModule, TranslocoPipe],
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SecretField), multi: true },
  ],
  template: `
    <div class="secret-field">
      <span class="secret-field__label">{{ labelKey() | transloco }}</span>
      <span class="secret-field__status">
        @if (status().configured) {
          {{ 'forms.secret.configured' | transloco }}
        } @else {
          {{ 'forms.secret.notConfigured' | transloco }}
        }
      </span>
      @if (status().changedAt; as changedAt) {
        <span class="secret-field__changed">
          {{
            'forms.secret.changed'
              | transloco
                : {
                    date: changedAt | date: 'short',
                    by: status().changedBy ?? ('forms.secret.unknownUser' | transloco),
                  }
          }}
        </span>
      }
      @if (editing()) {
        <mat-form-field class="secret-field__input">
          <mat-label>{{ 'forms.secret.new' | transloco }}</mat-label>
          <input
            matInput
            type="password"
            autocomplete="new-password"
            spellcheck="false"
            [disabled]="disabled()"
            [value]="value() ?? ''"
            (input)="type($any($event.target).value)"
            (blur)="touched()"
          />
        </mat-form-field>
        <button mat-button type="button" class="secret-field__cancel" (click)="cancel()">
          {{ 'forms.secret.cancel' | transloco }}
        </button>
      } @else {
        <button
          mat-stroked-button
          type="button"
          class="secret-field__edit"
          [disabled]="disabled()"
          (click)="editing.set(true)"
        >
          {{ (status().configured ? 'forms.secret.change' : 'forms.secret.set') | transloco }}
        </button>
      }
    </div>
  `,
  styles: `
    .secret-field {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
    }

    .secret-field__label {
      font-weight: 500;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretField implements ControlValueAccessor {
  readonly labelKey = input.required<string>();
  readonly status = input.required<SecretStatus>();

  protected readonly editing = signal(false);
  protected readonly value = signal<string | null>(null);
  protected readonly disabled = signal(false);

  private onChange: (value: string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  /** Only null (keep) or a secret the user typed in this form; never the stored one. */
  writeValue(value: string | null): void {
    this.value.set(value || null);
    if (!value) {
      this.editing.set(false);
    }
  }

  registerOnChange(fn: (value: string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  protected type(value: string): void {
    this.value.set(value || null);
    this.onChange(value || null);
  }

  protected touched(): void {
    this.onTouched();
  }

  /** Back to the stored secret: nothing typed is kept. */
  protected cancel(): void {
    this.editing.set(false);
    this.value.set(null);
    this.onChange(null);
    this.onTouched();
  }
}
