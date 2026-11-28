import { AbstractControl, ValidationErrors } from '@angular/forms';

/**
 * Spanish tax identifiers, checked like the backend (`SpanishTaxId` and `TaxIds` in `shared`), so a
 * value the form accepts is never refused by the server, and the other way round.
 */

/**
 * What Java's `Character.isWhitespace` or `Character.isSpaceChar` count as space, plus the dash:
 * the characters `TaxIds.normalize` removes.
 */
// The information separators U+001C–U+001F are whitespace for Java, so they are removed too.
// eslint-disable-next-line no-control-regex
const REMOVED = /[-\t\n\v\f\r\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}]/gu;

const DNI = /^[0-9]{8}[A-Z]$/;
const NIE = /^[XYZ][0-9]{7}[A-Z]$/;
const CIF = /^[ABCDEFGHJNPQRSUVW][0-9]{7}[0-9A-J]$/;

/** The check letter of a DNI or NIE, by the number modulo 23. */
const DNI_LETTERS = 'TRWAGMYFPDXBNJZSQVHLCKE';
/** The check letter of a CIF, by its check digit. */
const CIF_LETTERS = 'JABCDEFGHI';
/** CIF types whose check character is always a digit, and those whose check character is always a letter. */
const CIF_DIGIT_TYPES = 'ABEH';
const CIF_LETTER_TYPES = 'NPQRSW';

/**
 * The value in upper case, without whitespace or dashes (`b-1234 5674` is `B12345674`), as the
 * backend stores it. Anything else is left as it is, for the validator to refuse.
 */
export function normalizeTaxId(value: string): string {
  return value.replace(REMOVED, '').toUpperCase();
}

/**
 * Whether an already normalized value is a CIF (`B12345674`), a DNI (`12345678Z`) or a NIE
 * (`X1234567L`) with the right check character. The special NIFs starting with K, L or M are not
 * accepted.
 */
export function isSpanishTaxId(value: string): boolean {
  if (DNI.test(value)) {
    return dniLetter(Number(value.slice(0, 8))) === value[8];
  }
  if (NIE.test(value)) {
    const prefix = 'XYZ'.indexOf(value[0]);
    return dniLetter(Number(`${prefix}${value.slice(1, 8)}`)) === value[8];
  }
  return CIF.test(value) && validCif(value);
}

/**
 * Validator for a tax id field: `{ taxId: true }` when the normalized value is not valid. An empty
 * value is left to `Validators.required`. The form sends `normalizeTaxId(value)`.
 */
export function spanishTaxId(control: AbstractControl<string | null>): ValidationErrors | null {
  const value = control.value;
  if (value === null || value === '') {
    return null;
  }
  return isSpanishTaxId(normalizeTaxId(value)) ? null : { taxId: true };
}

function dniLetter(number: number): string {
  return DNI_LETTERS[number % 23];
}

function validCif(value: string): boolean {
  let sum = 0;
  for (let i = 1; i <= 7; i++) {
    const digit = Number(value[i]);
    if (i % 2 === 1) {
      // Odd positions are doubled, and the two digits of the result added.
      const doubled = digit * 2;
      sum += Math.floor(doubled / 10) + (doubled % 10);
    } else {
      sum += digit;
    }
  }
  const checkDigit = (10 - (sum % 10)) % 10;
  const type = value[0];
  const check = value[8];
  const digitMatches = check === String(checkDigit);
  const letterMatches = check === CIF_LETTERS[checkDigit];
  if (CIF_DIGIT_TYPES.includes(type)) {
    return digitMatches;
  }
  if (CIF_LETTER_TYPES.includes(type)) {
    return letterMatches;
  }
  return digitMatches || letterMatches;
}
