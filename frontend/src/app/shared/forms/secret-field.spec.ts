import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { es, translocoTesting } from '../../../testing/i18n';
import { SecretField, SecretStatus } from './secret-field';

@Component({
  imports: [SecretField, ReactiveFormsModule],
  template: `
    <app-secret-field [labelKey]="labelKey" [status]="status()" [formControl]="token" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  readonly labelKey = 'forms.secret.new';
  readonly status = signal<SecretStatus>({ configured: false, changedAt: null, changedBy: null });
  readonly token = new FormControl<string | null>(null);
}

describe('SecretField', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [Host, translocoTesting()] });
    fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function configured(): Promise<void> {
    fixture.componentInstance.status.set({
      configured: true,
      changedAt: '2026-11-20T10:30:00Z',
      changedBy: 'Ana Prueba',
    });
    await fixture.whenStable();
  }

  it('says when there is no secret', () => {
    expect(root().textContent).toContain(es.forms.secret.notConfigured);
    expect(root().querySelector('.secret-field__edit')?.textContent).toContain(es.forms.secret.set);
  });

  it('says only that there is one, and who changed it and when, never any character of it', async () => {
    await configured();

    const text = root().textContent ?? '';
    expect(text).toContain(es.forms.secret.configured);
    expect(text).toContain('Ana Prueba');
    expect(text).toContain('Último cambio:');
    expect(text).not.toContain('•');
    expect(root().querySelector('input')).toBeNull();
  });

  it('keeps the stored secret until the user types a new one', async () => {
    await configured();
    (root().querySelector('.secret-field__edit') as HTMLButtonElement).click();
    await fixture.whenStable();

    const input = root().querySelector('input') as HTMLInputElement;
    expect(input.type).toBe('password');
    expect(input.autocomplete).toBe('new-password');
    expect(input.value).toBe('');
    expect(fixture.componentInstance.token.value).toBeNull();

    input.value = 'nuevo-token-de-prueba';
    input.dispatchEvent(new Event('input'));

    expect(fixture.componentInstance.token.value).toBe('nuevo-token-de-prueba');
  });

  it('forgets what was typed when the user cancels', async () => {
    (root().querySelector('.secret-field__edit') as HTMLButtonElement).click();
    await fixture.whenStable();
    const input = root().querySelector('input') as HTMLInputElement;
    input.value = 'nuevo-token-de-prueba';
    input.dispatchEvent(new Event('input'));

    (root().querySelector('.secret-field__cancel') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.token.value).toBeNull();
    expect(root().querySelector('input')).toBeNull();
  });

  it('closes the input when the form is reset, as after saving', async () => {
    (root().querySelector('.secret-field__edit') as HTMLButtonElement).click();
    await fixture.whenStable();

    fixture.componentInstance.token.reset();
    await fixture.whenStable();

    expect(root().querySelector('input')).toBeNull();
  });
});
