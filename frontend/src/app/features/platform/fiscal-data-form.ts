import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { correctFiscalData } from '../../api/fn/clinic-administration-controller/correct-fiscal-data';
import { PlatformClinicDetails } from '../../api/models/platform-clinic-details';
import { FormActions } from '../../shared/forms/form-actions';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { normalizeTaxId, spanishTaxId } from '../../shared/forms/tax-id';
import { ControlError } from '../../shared/forms/control-error';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { CLINIC_NAME_MAX } from './new-clinic-form';

/**
 * Corrects a clinic's legal name and tax id (PUT /api/v1/platform/clinics/{id}/fiscal-data, M01-T11),
 * with the version the user saw: if someone changed the clinic meanwhile, the backend answers 409
 * and the form shows its explanation. The clinic only sees these data; the platform corrects them.
 */
@Component({
  selector: 'app-fiscal-data-form',
  imports: [
    ControlError,
    FormActions,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h4 id="fiscal-data-title">{{ 'platform.fiscal.title' | transloco }}</h4>
    <form class="platform-form" [attr.aria-labelledby]="'fiscal-data-title'" [formGroup]="form">
      <mat-form-field>
        <mat-label>{{ 'platform.fields.legalName' | transloco }}</mat-label>
        <input matInput formControlName="legalName" autocomplete="off" required />
        <mat-error><app-control-error [control]="form.controls.legalName" /></mat-error>
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ 'platform.fields.taxId' | transloco }}</mat-label>
        <input
          matInput
          formControlName="taxId"
          autocomplete="off"
          spellcheck="false"
          [attr.autocapitalize]="'characters'"
          required
        />
        <mat-error><app-control-error [control]="form.controls.taxId" /></mat-error>
      </mat-form-field>
      <app-form-actions
        [form]="form"
        [saving]="saving()"
        [failure]="failure()"
        (save)="save()"
        (discard)="discard()"
      />
    </form>
    <p class="platform-notice" role="status">
      @if (saved()) {
        {{ 'platform.fiscal.saved' | transloco }}
      }
    </p>
  `,
  styleUrl: './platform-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalDataForm {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly clinic = input.required<PlatformClinicDetails>();
  /** The clinic with the corrected data and its new version. */
  readonly changed = output<PlatformClinicDetails>();

  protected readonly form = new FormGroup({
    legalName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(CLINIC_NAME_MAX)],
    }),
    taxId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, spanishTaxId],
    }),
  });
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly saved = signal(false);

  constructor() {
    // Another clinic, or a new version of this one: the form shows its current data.
    effect(() => {
      const clinic = this.clinic();
      untracked(() => {
        this.form.reset({ legalName: clinic.legalName ?? '', taxId: clinic.taxId ?? '' });
        this.failure.set(null);
      });
    });
  }

  protected async save(): Promise<void> {
    this.saved.set(false);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    const clinic = this.clinic();
    const { legalName, taxId } = this.form.getRawValue();
    this.saving.set(true);
    this.failure.set(null);
    try {
      const corrected = await this.api.invoke(correctFiscalData, {
        id: clinic.id ?? '',
        body: { legalName, taxId: normalizeTaxId(taxId), version: clinic.version ?? 0 },
      });
      this.changed.emit(corrected);
      this.saved.set(true);
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    const clinic = this.clinic();
    this.form.reset({ legalName: clinic.legalName ?? '', taxId: clinic.taxId ?? '' });
    this.failure.set(null);
  }
}
