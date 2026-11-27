import { HttpErrorResponse } from '@angular/common/http';
import { FormArray, FormControl, FormGroup, Validators } from '@angular/forms';

import { toSaveFailure } from './save-failure';

function form() {
  return new FormGroup({
    name: new FormControl('Clínica Norte', { nonNullable: true, validators: Validators.required }),
    hours: new FormArray([new FormGroup({ opens: new FormControl('09:00') })]),
  });
}

function response(status: number, body: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

describe('toSaveFailure', () => {
  it('puts each refused field on its control, also inside lists', () => {
    const edited = form();

    const failure = toSaveFailure(
      edited,
      response(400, {
        title: 'Datos no válidos',
        errors: [
          { field: 'name', message: 'El nombre es obligatorio.' },
          { field: 'hours[0].opens', message: 'La hora no es válida.' },
        ],
      }),
    );

    expect(failure).toEqual({ kind: 'validation' });
    expect(edited.controls.name.errors).toEqual({ server: 'El nombre es obligatorio.' });
    expect(edited.controls.name.touched).toBe(true);
    expect(edited.get('hours.0.opens')?.errors).toEqual({ server: 'La hora no es válida.' });
  });

  it('clears the server error when the user edits the field', () => {
    const edited = form();
    toSaveFailure(edited, response(400, { errors: [{ field: 'name', message: 'Repetido.' }] }));

    edited.controls.name.setValue('Clínica Sur');

    expect(edited.controls.name.errors).toBeNull();
  });

  it('shows the problem when a refused field is not in the form', () => {
    const failure = toSaveFailure(
      form(),
      response(400, {
        title: 'Datos no válidos',
        detail: 'Revisa la zona horaria.',
        errors: [{ field: 'timeZone', message: 'No existe.' }],
      }),
    );

    expect(failure).toEqual({
      kind: 'problem',
      title: 'Datos no válidos',
      detail: 'Revisa la zona horaria.',
    });
  });

  it('still puts the other fields on their controls when one is not in the form', () => {
    const edited = form();

    toSaveFailure(
      edited,
      response(400, {
        errors: [
          { field: 'timeZone', message: 'No existe.' },
          { field: 'name', message: 'El nombre es obligatorio.' },
        ],
      }),
    );

    expect(edited.controls.name.errors).toEqual({ server: 'El nombre es obligatorio.' });
  });

  it('shows the explanation of a conflict, such as a version conflict', () => {
    const failure = toSaveFailure(
      form(),
      response(409, {
        title: 'Datos modificados por otra persona',
        detail: 'Alguien ha cambiado estos datos mientras los editabas.',
      }),
    );

    expect(failure).toEqual({
      kind: 'problem',
      title: 'Datos modificados por otra persona',
      detail: 'Alguien ha cambiado estos datos mientras los editabas.',
    });
  });

  it('treats a server error, a lost connection or anything else as unexpected', () => {
    expect(toSaveFailure(form(), response(500, { detail: 'Internal' }))).toEqual({
      kind: 'unexpected',
    });
    expect(toSaveFailure(form(), response(0, null))).toEqual({ kind: 'unexpected' });
    expect(toSaveFailure(form(), new Error('boom'))).toEqual({ kind: 'unexpected' });
  });
});
