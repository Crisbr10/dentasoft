import { AbstractControl, FormGroupDirective, NgForm, ValidationErrors } from '@angular/forms';
import { ErrorStateMatcher } from '@angular/material/core';

import { PractitionerDetails } from '../../api/models/practitioner-details';

/** The clinical area («área clínica») of a practitioner, `PractitionerSpecialty` in the backend. */
export type Specialty = NonNullable<PractitionerDetails['specialty']>;
export type PractitionerStatus = NonNullable<PractitionerDetails['status']>;

/** In the backend's order; texts under `practitioners.areas`. */
export const SPECIALTIES: readonly Specialty[] = [
  'GENERAL_DENTISTRY',
  'ORTHODONTICS',
  'ENDODONTICS',
  'PERIODONTICS',
  'IMPLANTOLOGY',
  'ORAL_SURGERY',
  'PROSTHODONTICS',
  'PEDIATRIC_DENTISTRY',
  'DENTAL_HYGIENE',
];

/** The only area that needs no licence number. */
export const DENTAL_HYGIENE: Specialty = 'DENTAL_HYGIENE';

/**
 * The INE codes of the 50 provinces, Ceuta (51) and Melilla (52), in the alphabetical order of
 * their names (`practitioners.provinces`), as the select offers them.
 */
// prettier-ignore
export const PROVINCES: readonly string[] = [
  '15', '02', '03', '04', '01', '33', '05', '06', '08', '48', '09', '10', '11',
  '39', '12', '51', '13', '14', '16', '20', '17', '18', '19', '21', '22', '07',
  '23', '26', '35', '24', '25', '27', '28', '29', '52', '30', '31', '32', '34',
  '36', '37', '38', '40', '41', '42', '43', '44', '45', '46', '47', '49', '50',
];

/** As `PractitionerRoleRequest` in the backend. */
export const DISPLAY_NAME_MAX = 100;
const LICENSE_NUMBER_MIN = 3;
const LICENSE_NUMBER_MAX = 12;
const LICENSE_NUMBER = /^[0-9A-Za-z]+([-/][0-9A-Za-z]+)?$/;
const COLOR = /^#[0-9A-Fa-f]{6}$/;

/**
 * The backend's `PersonNames.PATTERN` with `@NoControlCharacters`: at least one letter, no control
 * or format characters, except the zero-width non-joiner and joiner between two letters or marks.
 */
const PERSON_NAME = /^(?=.*\p{L})(?:[^\p{Cc}\p{Cf}]|(?<=[\p{L}\p{M}])[‌‍](?=[\p{L}\p{M}]))*$/u;

/** A name the backend accepts; an empty one is left to `Validators.required`. */
export function personName(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value ?? '';
  return value === '' || PERSON_NAME.test(value) ? null : { personName: true };
}

/**
 * Letters and digits, with at most one `-` or `/` between them, 3 to 12 characters. Checked on the
 * value without surrounding spaces, which is what is sent; an empty one means no licence.
 */
export function licenseNumber(control: AbstractControl<string>): ValidationErrors | null {
  const value = (control.value ?? '').trim();
  if (value === '') {
    return null;
  }
  const fits = value.length >= LICENSE_NUMBER_MIN && value.length <= LICENSE_NUMBER_MAX;
  return fits && LICENSE_NUMBER.test(value) ? null : { licenseNumber: true };
}

export function agendaColor(control: AbstractControl<string>): ValidationErrors | null {
  return COLOR.test(control.value ?? '') ? null : { agendaColor: true };
}

/** The errors of the licence as a whole, named as the backend's `@AssertTrue` properties. */
export type LicenseError = 'licenseComplete' | 'licenseGiven';

/**
 * The backend's `isLicenseComplete` and `isLicenseGiven`, on the form: the province and the number
 * go together, and a licence is required unless the area is dental hygiene.
 */
export function licenseRules(form: AbstractControl): ValidationErrors | null {
  const province = (form.get('licenseProvince')?.value as string | null) ?? null;
  const number = ((form.get('licenseNumber')?.value as string | null) ?? '').trim();
  const specialty = (form.get('specialty')?.value as Specialty | null) ?? null;
  if ((province === null) !== (number === '')) {
    return { licenseComplete: true };
  }
  if (specialty !== null && specialty !== DENTAL_HYGIENE && number === '') {
    return { licenseGiven: true };
  }
  return null;
}

export function licenseError(form: AbstractControl): LicenseError | null {
  if (form.hasError('licenseComplete')) {
    return 'licenseComplete';
  }
  return form.hasError('licenseGiven') ? 'licenseGiven' : null;
}

/** The licence fields show as invalid too while the licence as a whole is, once touched. */
export const licenseErrorState: ErrorStateMatcher = {
  isErrorState(control: AbstractControl | null, form: FormGroupDirective | NgForm | null) {
    if (!control) {
      return false;
    }
    const invalid = control.invalid || (control.parent !== null && !!licenseError(control.parent));
    return invalid && (control.touched || !!form?.submitted);
  },
};
