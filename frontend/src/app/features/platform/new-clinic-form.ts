import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { create } from '../../api/fn/clinic-administration-controller/create';
import { PlatformClinicDetails } from '../../api/models/platform-clinic-details';
import { FormActions } from '../../shared/forms/form-actions';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { normalizeTaxId, spanishTaxId } from '../../shared/forms/tax-id';
import { ControlError } from '../../shared/forms/control-error';
import { focusFirstInvalid } from '../../shared/forms/form-focus';

/** Name and legal name, as long as the backend accepts them. */
export const CLINIC_NAME_MAX = 200;

/**
 * Creates a clinic (POST /api/v1/platform/clinics) with its name and fiscal data; the timezone and
 * the locale are the backend's defaults, and the clinic changes them later. The tax id is sent
 * normalized, as the backend stores it.
 */
@Component({
  selector: 'app-new-clinic-form',
  imports: [
    ControlError,
    FormActions,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="new-clinic-title">{{ 'platform.create.title' | transloco }}</h3>
    <p>{{ 'platform.create.intro' | transloco }}</p>
    <form class="platform-form" [attr.aria-labelledby]="'new-clinic-title'" [formGroup]="form">
      <mat-form-field>
        <mat-label>{{ 'platform.fields.name' | transloco }}</mat-label>
        <input matInput formControlName="name" autocomplete="off" required />
        <mat-error><app-control-error [control]="form.controls.name" /></mat-error>
      </mat-form-field>
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
  `,
  styleUrl: './platform-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewClinicForm {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The clinic just created, as the backend returned it. */
  readonly created = output<PlatformClinicDetails>();

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(CLINIC_NAME_MAX)],
    }),
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

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    const { name, legalName, taxId } = this.form.getRawValue();
    this.saving.set(true);
    this.failure.set(null);
    try {
      const clinic = await this.api.invoke(create, {
        body: { name, legalName, taxId: normalizeTaxId(taxId) },
      });
      this.form.reset();
      this.created.emit(clinic);
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.form.reset();
    this.failure.set(null);
  }
}
