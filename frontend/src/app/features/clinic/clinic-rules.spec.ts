import { FormArray, FormControl, FormGroup } from '@angular/forms';

import { contactPhone, dayRules, plainText, slotRules, timeZones } from './clinic-rules';

function day(...slots: [string, string][]): FormArray {
  return new FormArray(
    slots.map(
      ([opening, closing]) =>
        new FormGroup({
          openingTime: new FormControl(opening),
          closingTime: new FormControl(closing),
        }),
    ),
  );
}

describe('clinic rules', () => {
  afterEach(() => vi.restoreAllMocks());

  describe('timeZones', () => {
    it('lists the browser zones sorted, with Europe/Madrid and the clinic zone', () => {
      vi.spyOn(Intl, 'supportedValuesOf').mockReturnValue(['Europe/Paris', 'Africa/Ceuta']);

      expect(timeZones('Etc/UTC')).toEqual([
        'Africa/Ceuta',
        'Etc/UTC',
        'Europe/Madrid',
        'Europe/Paris',
      ]);
    });

    it('lists each zone once', () => {
      vi.spyOn(Intl, 'supportedValuesOf').mockReturnValue(['Europe/Madrid']);

      expect(timeZones('Europe/Madrid')).toEqual(['Europe/Madrid']);
    });

    it('still offers Europe/Madrid where the browser lists no zones', () => {
      const original = Object.getOwnPropertyDescriptor(Intl, 'supportedValuesOf')!;
      Object.defineProperty(Intl, 'supportedValuesOf', { value: undefined, configurable: true });
      try {
        expect(timeZones(null)).toEqual(['Europe/Madrid']);
      } finally {
        Object.defineProperty(Intl, 'supportedValuesOf', original);
      }
    });
  });

  describe('dayRules', () => {
    it('accepts slots that touch and slots given out of order', () => {
      expect(dayRules(day(['16:00', '20:00'], ['09:00', '14:00'], ['14:00', '16:00']))).toBeNull();
    });

    it('finds a slot inside another one, even after a shorter one', () => {
      expect(dayRules(day(['09:00', '20:00'], ['10:00', '11:00'], ['12:00', '13:00']))).toEqual({
        overlapping: true,
      });
    });

    it('leaves incomplete slots to their own rules', () => {
      expect(dayRules(day(['09:00', ''], ['10:00', '11:00']))).toBeNull();
    });
  });

  it('wants a slot to close after it opens', () => {
    const slot = (opening: string, closing: string) =>
      slotRules(
        new FormGroup({
          openingTime: new FormControl(opening),
          closingTime: new FormControl(closing),
        }),
      );

    expect(slot('09:00', '09:00')).toEqual({ closesBeforeOpening: true });
    expect(slot('09:00:00', '09:01')).toBeNull();
  });

  it('refuses blank text and control characters, as the backend', () => {
    const check = (value: string) => plainText(new FormControl(value));

    expect(check('Gabinete 1')).toBeNull();
    expect(check('   ')).toEqual({ plainText: true });
    expect(check('Gabinete\n1')).toEqual({ plainText: true });
    expect(check('Gabinete\u0085')).toEqual({ plainText: true });
  });

  it('takes a phone of digits and spaces with an optional leading +', () => {
    const check = (value: string) => contactPhone(new FormControl(value));

    expect(check('')).toBeNull();
    expect(check('+34 910 000 000')).toBeNull();
    expect(check('910-000-000')).toEqual({ contactPhone: true });
    expect(check('34+910')).toEqual({ contactPhone: true });
  });
});
