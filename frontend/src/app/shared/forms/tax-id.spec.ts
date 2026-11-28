import { FormControl } from '@angular/forms';

import { isSpanishTaxId, normalizeTaxId, spanishTaxId } from './tax-id';

// The same cases as the backend's SpanishTaxIdTests, so both sides accept and refuse the same values.
describe('tax id', () => {
  it.each([
    'B12345674',
    'B00000000',
    'A58818501',
    'B87654323',
    'Q1234567D',
    'P0000000J',
    'G12345674',
    'G1234567D',
    '12345678Z',
    '00000000T',
    'X1234567L',
    'Y1234567X',
    'Z1234567R',
  ])('accepts %s', (value) => {
    expect(isSpanishTaxId(value)).toBe(true);
  });

  it.each([
    'B12345678',
    'Q1234567E',
    '12345678A',
    'X1234567A',
    'B1234567D',
    'Q12345674',
    'I12345674',
    'O12345674',
    'T12345674',
    'B1234567',
    'B123456740',
    '1234567Z',
    'B1234567-4',
    'b12345674',
    ' B12345674',
    'XX1234567',
    'K1234567L',
    '',
  ])('refuses %j', (value) => {
    expect(isSpanishTaxId(value)).toBe(false);
  });

  it.each([
    ['b-12345674', 'B12345674'],
    ['B12345674', 'B12345674'],
    [' b 1234 5674 ', 'B12345674'],
    ['x-1234567-l', 'X1234567L'],
    ['12.345.678-z', '12.345.678Z'],
    ['B1234\t5674 ', 'B12345674'],
    ['B1234 5674', 'B12345674'],
  ])('normalizes %j to %s', (raw, normalized) => {
    expect(normalizeTaxId(raw)).toBe(normalized);
  });

  // Java's isWhitespace and isSpaceChar do not cover the byte order mark, so the backend keeps it.
  it('keeps characters the backend does not remove', () => {
    expect(normalizeTaxId('B1234﻿5674')).toBe('B1234﻿5674');
  });

  describe('validator', () => {
    it('validates the normalized value', () => {
      expect(new FormControl(' b-1234 5674 ', spanishTaxId).errors).toBeNull();
    });

    it('refuses a wrong check character', () => {
      expect(new FormControl('B12345678', spanishTaxId).errors).toEqual({ taxId: true });
    });

    it('leaves an empty value to required', () => {
      expect(new FormControl('', spanishTaxId).errors).toBeNull();
      expect(new FormControl(null, spanishTaxId).errors).toBeNull();
    });
  });
});
