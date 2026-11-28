import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, merge } from 'rxjs';

import { Api } from '../../api/api';
import { clinic } from '../../api/fn/clinic-configuration-controller/clinic';
import { updateClinic } from '../../api/fn/clinic-configuration-controller/update-clinic';
import { ClinicConfigurationDetails } from '../../api/models/clinic-configuration-details';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import {
  CLINIC_NAME_MAX,
  DEFAULT_TIME_ZONE,
  knownTimeZone,
  plainText,
  timeZones,
} from './clinic-rules';

/** «Europe/Madrid» matches «madrid», «europe madrid» and «Europe/Ma». */
function normalized(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s_/]+/g, ' ')
    .trim();
}

/**
 * The clinic's own data (GET and PUT /api/v1/clinic, M01-T06): its name, which ADMIN_CLINICA
 * edits with its time zone, and its legal name and tax id, which everyone only sees: the platform
 * corrects them (M01-T11). The time zones are those the browser knows; the backend checks the one
 * chosen again. The other roles see the same data as text.
 */
@Component({
  selector: 'app-clinic-data-form',
  imports: [
    ControlError,
    FormActions,
    MatAutocompleteModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="clinic-data-title">{{ 'clinic.data.title' | transloco }}</h3>
    @if (loading()) {
      <p class="clinic-block__loading" role="status">
        <mat-spinner diameter="20" aria-hidden="true" />
        <span>{{ 'clinic.data.loading' | transloco }}</span>
      </p>
    } @else if (loadFailed()) {
      <p role="alert">{{ 'clinic.data.loadError' | transloco }}</p>
      <button mat-stroked-button type="button" (click)="load()">
        <mat-icon svgIcon="refresh" />
        {{ 'clinic.retry' | transloco }}
      </button>
    }

    @if (details(); as details) {
      @if (canEdit()) {
        <form
          class="clinic-block__form"
          [attr.aria-labelledby]="'clinic-data-title'"
          [formGroup]="form"
        >
          <mat-form-field>
            <mat-label>{{ 'clinic.data.name' | transloco }}</mat-label>
            <input matInput formControlName="name" autocomplete="organization" required />
            <mat-error>
              @if (nameInvalid()) {
                {{ 'clinic.errors.plainText' | transloco }}
              } @else {
                <app-control-error [control]="form.controls.name" />
              }
            </mat-error>
          </mat-form-field>

          <dl class="clinic-block__fiscal">
            <dt>{{ 'clinic.data.legalName' | transloco }}</dt>
            <dd>{{ details.legalName }}</dd>
            <dt>{{ 'clinic.data.taxId' | transloco }}</dt>
            <dd>{{ details.taxId }}</dd>
          </dl>
          <p class="clinic-block__hint">
            {{ 'clinic.data.fiscalHint' | transloco }}
          </p>

          <mat-form-field>
            <mat-label>{{ 'clinic.data.timezone' | transloco }}</mat-label>
            <input
              matInput
              formControlName="timezone"
              autocomplete="off"
              spellcheck="false"
              required
              [attr.autocapitalize]="'none'"
              [matAutocomplete]="zonesPanel"
            />
            <mat-autocomplete #zonesPanel="matAutocomplete" [hideSingleSelectionIndicator]="true">
              @for (zone of shownZones(); track zone) {
                <mat-option [value]="zone">{{ zone }}</mat-option>
              }
            </mat-autocomplete>
            <mat-hint>{{ 'clinic.data.timezoneHint' | transloco }}</mat-hint>
            <mat-error>
              @if (zoneInvalid()) {
                {{ 'clinic.errors.timeZone' | transloco }}
              } @else {
                <app-control-error [control]="form.controls.timezone" />
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
              {{ 'clinic.data.saved' | transloco }}
            }
          </p>
        </form>
      } @else {
        <dl class="clinic-block__values">
          <dt>{{ 'clinic.data.name' | transloco }}</dt>
          <dd>{{ details.name }}</dd>
          <dt>{{ 'clinic.data.legalName' | transloco }}</dt>
          <dd>{{ details.legalName }}</dd>
          <dt>{{ 'clinic.data.taxId' | transloco }}</dt>
          <dd>{{ details.taxId }}</dd>
          <dt>{{ 'clinic.data.timezone' | transloco }}</dt>
          <dd>{{ details.timezone }}</dd>
        </dl>
      }
    }
  `,
  styleUrl: './clinic-block.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicDataForm implements OnInit {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** ADMIN_CLINICA (access EDIT) edits; the other roles only see the data. */
  readonly canEdit = input(false);

  protected readonly details = signal<ClinicConfigurationDetails | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly saved = signal(false);

  private readonly zones = computed(() => timeZones(this.details()?.timezone));

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(CLINIC_NAME_MAX), plainText],
    }),
    timezone: new FormControl(DEFAULT_TIME_ZONE, {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });

  private readonly zoneText = toSignal(this.form.controls.timezone.valueChanges, {
    initialValue: DEFAULT_TIME_ZONE,
  });

  /** Every zone while the field holds one; those that match what is typed otherwise. */
  protected readonly shownZones = computed(() => {
    const zones = this.zones();
    const text = this.zoneText() ?? '';
    if (text === '' || zones.includes(text)) {
      return zones;
    }
    const query = normalized(text);
    return zones.filter((zone) => normalized(zone).includes(query));
  });

  protected readonly nameInvalid = this.watch(() => {
    const control = this.form.controls.name;
    return control.hasError('plainText') && !control.hasError('server');
  });

  protected readonly zoneInvalid = this.watch(() => {
    const control = this.form.controls.timezone;
    return control.hasError('timeZone') && !control.hasError('server');
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      this.show(await this.api.invoke(clinic, {}));
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
      const details = await this.api.invoke(updateClinic, {
        body: {
          name: value.name.trim(),
          timezone: value.timezone,
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

  private show(details: ClinicConfigurationDetails): void {
    this.details.set(details);
    this.form.controls.timezone.setValidators([Validators.required, knownTimeZone(this.zones())]);
    this.fill(details);
  }

  private fill(details: ClinicConfigurationDetails | null): void {
    this.form.reset({
      name: details?.name ?? '',
      timezone: details?.timezone ?? DEFAULT_TIME_ZONE,
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
