import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';

import { es, translocoTesting } from '../../../testing/i18n';
import { FormActions } from './form-actions';
import { SaveFailure } from './save-failure';

@Component({
  imports: [FormActions, ReactiveFormsModule],
  template: `
    <form [formGroup]="form">
      <input formControlName="name" />
      <app-form-actions
        [form]="form"
        [saving]="saving()"
        [failure]="failure()"
        [saveKey]="saveKey()"
        (save)="saved = saved + 1"
        (discard)="form.reset({ name: 'Clínica Norte' })"
      />
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  readonly form = new FormGroup({ name: new FormControl('Clínica Norte') });
  readonly saving = signal(false);
  readonly failure = signal<SaveFailure | null>(null);
  readonly saveKey = signal<string | undefined>(undefined);
  saved = 0;
}

describe('FormActions', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [Host, translocoTesting()] });
    fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function button(name: 'save' | 'discard'): HTMLButtonElement {
    return root().querySelector(`.form-actions__${name}`) as HTMLButtonElement;
  }

  async function type(value: string): Promise<void> {
    const input = root().querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  it('offers nothing to save or discard while nothing changed', () => {
    expect(button('save').disabled).toBe(true);
    expect(button('discard').disabled).toBe(true);
  });

  it('labels the save button with the default text, or with the one the form gives', async () => {
    expect(button('save').textContent?.trim()).toBe(es.forms.save);

    fixture.componentInstance.saveKey.set('dialogs.cancel');
    await fixture.whenStable();

    expect(button('save').textContent?.trim()).toBe(es.dialogs.cancel);
  });

  it('saves the changes', async () => {
    await type('Clínica Sur');

    button('save').click();

    expect(button('save').disabled).toBe(false);
    expect(fixture.componentInstance.saved).toBe(1);
  });

  it('discards the changes', async () => {
    await type('Clínica Sur');

    button('discard').click();
    await fixture.whenStable();

    expect(fixture.componentInstance.form.value).toEqual({ name: 'Clínica Norte' });
    expect(button('discard').disabled).toBe(true);
  });

  it('blocks both buttons while saving', async () => {
    await type('Clínica Sur');
    fixture.componentInstance.saving.set(true);
    await fixture.whenStable();

    expect(button('save').disabled).toBe(true);
    expect(button('save').textContent).toContain(es.forms.saving);
    expect(button('discard').disabled).toBe(true);
  });

  it.each<[SaveFailure, string[]]>([
    [{ kind: 'validation' }, [es.forms.invalid]],
    [
      {
        kind: 'problem',
        title: 'Datos modificados por otra persona',
        detail: 'Vuelve a cargarlos.',
      },
      ['Datos modificados por otra persona', 'Vuelve a cargarlos.'],
    ],
    [{ kind: 'problem', title: null, detail: null }, [es.forms.unexpected]],
    [{ kind: 'unexpected' }, [es.forms.unexpected]],
  ])('explains why saving failed: %j', async (failure, texts) => {
    fixture.componentInstance.failure.set(failure);
    await fixture.whenStable();

    const alert = root().querySelector('[role="alert"]');
    for (const text of texts) {
      expect(alert?.textContent).toContain(text);
    }
  });
});
