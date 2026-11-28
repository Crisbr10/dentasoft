import { AbstractControl, ValidationErrors } from '@angular/forms';

import { LocationDetails } from '../../api/models/location-details';
import { Slot } from '../../api/models/slot';

export type DayOfWeek = NonNullable<Slot['dayOfWeek']>;
export type LocationStatus = NonNullable<LocationDetails['status']>;

/** The days in the order of the week the clinic reads it, as the backend sorts them. */
export const DAYS: readonly DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

/** The clinic's time zone when it has none yet (V003), and where Dentasoft starts. */
export const DEFAULT_TIME_ZONE = 'Europe/Madrid';

/** The limits of `UpdateClinicRequest`, `UpdateClinicSettingsRequest` and `LocationRequest`. */
export const CLINIC_NAME_MAX = 200;
export const LOCATION_NAME_MAX = 100;
export const CONTACT_EMAIL_MAX = 254;
export const APPOINTMENT_MINUTES_MIN = 5;
export const APPOINTMENT_MINUTES_MAX = 480;
/** `ReplaceOpeningHoursRequest.MAX_SLOTS`: four slots a day at most, on average. */
export const MAX_SLOTS = 28;

const CONTACT_PHONE = /^\+?[0-9 ]{0,31}$/;
const COLOR = /^#[0-9A-Fa-f]{6}$/;
// U+0000–U+001F and U+007F–U+009F, as Java's Character.isISOControl (NoControlCharacters).
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/;

/**
 * The time zones the browser knows (`Intl.supportedValuesOf`), sorted, always with
 * {@link DEFAULT_TIME_ZONE} and the clinic's `current` one, even if this browser does not list it:
 * the backend checks the zone again against Java's (`@KnownTimeZone`).
 */
export function timeZones(current: string | null | undefined): readonly string[] {
  const known =
    typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  const zones = new Set([...known, DEFAULT_TIME_ZONE]);
  if (current) {
    zones.add(current);
  }
  return [...zones].sort((a, b) => a.localeCompare(b));
}

/** One of `zones`: the autocomplete accepts typing, but only a listed zone is a value. */
export function knownTimeZone(zones: readonly string[]) {
  return (control: AbstractControl<string | null>): ValidationErrors | null => {
    const value = control.value ?? '';
    return value === '' || zones.includes(value) ? null : { timeZone: true };
  };
}

/** Text with something visible and no control characters (`@NotBlank @NoControlCharacters`). */
export function plainText(control: AbstractControl<string | null>): ValidationErrors | null {
  const value = control.value ?? '';
  if (value === '') {
    return null;
  }
  return value.trim() !== '' && !CONTROL_CHARACTERS.test(value) ? null : { plainText: true };
}

/** Digits and spaces, with an optional leading «+» (`UpdateClinicSettingsRequest`). */
export function contactPhone(control: AbstractControl<string | null>): ValidationErrors | null {
  const value = control.value ?? '';
  return CONTACT_PHONE.test(value) ? null : { contactPhone: true };
}

export function roomColor(control: AbstractControl<string>): ValidationErrors | null {
  return COLOR.test(control.value ?? '') ? null : { roomColor: true };
}

/** `09:00:00` from the backend, or `09:00` from a time input, as `09:00`. */
export function wallClock(time: string | null | undefined): string {
  return (time ?? '').slice(0, 5);
}

/** A slot that closes after it opens; both `HH:mm`, which sort as text. */
export function slotRules(slot: AbstractControl): ValidationErrors | null {
  const opening = wallClock(slot.get('openingTime')?.value as string | null);
  const closing = wallClock(slot.get('closingTime')?.value as string | null);
  if (opening === '' || closing === '') {
    return null;
  }
  return opening < closing ? null : { closesBeforeOpening: true };
}

/**
 * The slots of a day do not overlap, as `ClinicOpeningHours.requireNoOverlaps` checks: sorted by
 * opening time, each opens no earlier than the latest closing before it. A slot may open when the
 * previous one closes.
 */
export function dayRules(day: AbstractControl): ValidationErrors | null {
  const slots = ((day.value as { openingTime: string; closingTime: string }[] | null) ?? [])
    .map((slot) => ({ opening: wallClock(slot.openingTime), closing: wallClock(slot.closingTime) }))
    .filter((slot) => slot.opening !== '' && slot.closing !== '' && slot.opening < slot.closing)
    .sort((a, b) => a.opening.localeCompare(b.opening));
  let latestClosing = '';
  for (const slot of slots) {
    if (slot.opening < latestClosing) {
      return { overlapping: true };
    }
    latestClosing = slot.closing > latestClosing ? slot.closing : latestClosing;
  }
  return null;
}
