import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, merge } from 'rxjs';

import { Api } from '../../api/api';
import { settings } from '../../api/fn/clinic-configuration-controller/settings';
import { updateSettings } from '../../api/fn/clinic-configuration-controller/update-settings';
import { SettingsDetails } from '../../api/models/settings-details';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import {
  APPOINTMENT_MINUTES_MAX,
  APPOINTMENT_MINUTES_MIN,
  CONTACT_EMAIL_MAX,
  contactPhone,
} from './clinic-rules';

/**
 * The clinic's settings (GET and PUT /api/v1/clinic/settings, M01-T06): how long an appointment
 * lasts unless told otherwise, and the email and phone patients use to reach the clinic, both
 * optional. ADMIN_CLINICA edits them with the version they saw; the other roles see them as text.
 */
@Component({
  selector: 'app-clinic-settings-form',
  imports: [
    ControlError,
    FormActions,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="clinic-settings-title">{{ 'clinic.settings.title' | transloco }}</h3>
    @if (loading()) {
      <p class="clinic-block__loading" role="status">
        <mat-spinner diameter="20" aria-hidden="true" />
        <span>{{ 'clinic.settings.loading' | transloco }}</span>
      </p>
    } @else if (loadFailed()) {
      <p role="alert">{{ 'clinic.settings.loadError' | transloco }}</p>
      <button mat-stroked-button type="button" (click)="load()">
        <mat-icon svgIcon="refresh" />
        {{ 'clinic.retry' | transloco }}
      </button>
    }

    @if (details(); as details) {
      @if (canEdit()) {
        <form
          class="clinic-block__form"
          [attr.aria-labelledby]="'clinic-settings-title'"
          [formGroup]="form"
        >
          <mat-form-field [subscriptSizing]="'dynamic'">
            <mat-label>{{ 'clinic.settings.appointmentMinutes' | transloco }}</mat-label>
            <input
              matInput
              type="number"
              formControlName="defaultAppointmentMinutes"
              required
              [min]="minutesMin"
              [max]="minutesMax"
            />
            <mat-hint>
              {{
                'clinic.settings.appointmentMinutesHint'
                  | transloco: { min: minutesMin, max: minutesMax }
              }}
            </mat-hint>
            <mat-error>
              @if (minutesInvalid()) {
                {{ 'clinic.errors.minutes' | transloco: { min: minutesMin, max: minutesMax } }}
              } @else {
                <app-control-error [control]="form.controls.defaultAppointmentMinutes" />
              }
            </mat-error>
          </mat-form-field>

          <mat-form-field>
            <mat-label>{{ 'clinic.settings.contactEmail' | transloco }}</mat-label>
            <input
              matInput
              type="email"
              formControlName="contactEmail"
              autocomplete="email"
              spellcheck="false"
              [attr.autocapitalize]="'none'"
            />
            <mat-error><app-control-error [control]="form.controls.contactEmail" /></mat-error>
          </mat-form-field>

          <mat-form-field [subscriptSizing]="'dynamic'">
            <mat-label>{{ 'clinic.settings.contactPhone' | transloco }}</mat-label>
            <input matInput type="tel" formControlName="contactPhone" autocomplete="tel" />
            <mat-hint>{{ 'clinic.settings.contactPhoneHint' | transloco }}</mat-hint>
            <mat-error>
              @if (phoneInvalid()) {
                {{ 'clinic.errors.contactPhone' | transloco }}
              } @else {
                <app-control-error [control]="form.controls.contactPhone" />
              }
            </mat-error>
          </mat-form-field>

          <app-form-actions
            [form]="form"
            [saving]="saving()"
            [failure]="failure()"
            (save)="save()"
            (discard)="discard()"
          />
          <p class="clinic-block__notice" role="status">
            @if (saved()) {
              {{ 'clinic.settings.saved' | transloco }}
            }
          </p>
        </form>
      } @else {
        <dl class="clinic-block__values">
          <dt>{{ 'clinic.settings.appointmentMinutes' | transloco }}</dt>
          <dd>
            {{
              'clinic.settings.minutesValue'
                | transloco: { minutes: details.defaultAppointmentMinutes }
            }}
          </dd>
          <dt>{{ 'clinic.settings.contactEmail' | transloco }}</dt>
          <dd>{{ details.contactEmail ?? ('clinic.notGiven' | transloco) }}</dd>
          <dt>{{ 'clinic.settings.contactPhone' | transloco }}</dt>
          <dd>{{ details.contactPhone ?? ('clinic.notGiven' | transloco) }}</dd>
        </dl>
      }
    }
  `,
  styleUrl: './clinic-block.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicSettingsForm implements OnInit {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** ADMIN_CLINICA (access EDIT) edits; the other roles only see the settings. */
  readonly canEdit = input(false);

  protected readonly minutesMin = APPOINTMENT_MINUTES_MIN;
  protected readonly minutesMax = APPOINTMENT_MINUTES_MAX;

  protected readonly details = signal<SettingsDetails | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly saved = signal(false);

  protected readonly form = new FormGroup({
    defaultAppointmentMinutes: new FormControl<number | null>(null, {
      validators: [
        Validators.required,
        Validators.min(APPOINTMENT_MINUTES_MIN),
        Validators.max(APPOINTMENT_MINUTES_MAX),
        Validators.pattern(/^\d+$/),
      ],
    }),
    contactEmail: new FormControl('', {
      nonNullable: true,
      validators: [Validators.email, Validators.maxLength(CONTACT_EMAIL_MAX)],
    }),
    contactPhone: new FormControl('', { nonNullable: true, validators: [contactPhone] }),
  });

  protected readonly minutesInvalid = this.watch(() => {
    const control = this.form.controls.defaultAppointmentMinutes;
    return (
      (control.hasError('min') || control.hasError('max') || control.hasError('pattern')) &&
      !control.hasError('server')
    );
  });

  protected readonly phoneInvalid = this.watch(() => {
    const control = this.form.controls.contactPhone;
    return control.hasError('contactPhone') && !control.hasError('server');
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      this.show(await this.api.invoke(settings, {}));
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected async save(): Promise<void> {
    this.saved.set(false);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.failure.set(null);
    try {
      // An empty email or phone is sent as such: the backend keeps it as «not given».
      const details = await this.api.invoke(updateSettings, {
        body: {
          defaultAppointmentMinutes: value.defaultAppointmentMinutes!,
          contactEmail: value.contactEmail.trim(),
          contactPhone: value.contactPhone.trim(),
          version: this.details()?.version ?? 0,
        },
      });
      this.show(details);
      this.saved.set(true);
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.fill(this.details());
    this.failure.set(null);
    this.saved.set(false);
  }

  private show(details: SettingsDetails): void {
    this.details.set(details);
    this.fill(details);
  }

  private fill(details: SettingsDetails | null): void {
    this.form.reset({
      defaultAppointmentMinutes: details?.defaultAppointmentMinutes ?? null,
      contactEmail: details?.contactEmail ?? '',
      contactPhone: details?.contactPhone ?? '',
    });
  }

  /** A value that follows the events of the form and its fields, as a signal. */
  private watch(read: () => boolean) {
    const controls = Object.values(this.form.controls);
    return toSignal(merge(this.form.events, ...controls.map((c) => c.events)).pipe(map(read)), {
      initialValue: read(),
    });
  }
}
