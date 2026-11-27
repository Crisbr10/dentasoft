import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatIconTestingModule } from '@angular/material/icon/testing';
import { Router, provideRouter } from '@angular/router';

import { es, translocoTesting } from '../../../testing/i18n';
import { Session } from '../auth/session';
import { ActiveClinic, ClinicRole } from '../clinic/active-clinic';
import { Shell } from './shell';

let created = 0;

/** A page that counts how many times it is created. */
@Component({
  selector: 'app-counting-page',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class CountingPage {
  constructor() {
    created++;
  }
}

describe('Shell', () => {
  let clinic: {
    id: ReturnType<typeof signal<string | null>>;
    role: ReturnType<typeof signal<ClinicRole | null>>;
    current: ReturnType<typeof signal<{ clinicId: string; name: string } | null>>;
    clinics: ReturnType<typeof signal<unknown[]>>;
    invitations: ReturnType<typeof signal<unknown[]>>;
  };
  let session: {
    isPlatformAdmin: ReturnType<typeof signal<boolean>>;
    userName: ReturnType<typeof signal<string | null>>;
    logout: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    created = 0;
    clinic = {
      id: signal<string | null>('a'),
      role: signal<ClinicRole | null>('RECEPCION'),
      current: signal<{ clinicId: string; name: string } | null>({
        clinicId: 'a',
        name: 'Clínica Norte',
      }),
      clinics: signal<unknown[]>([{ clinicId: 'a' }, { clinicId: 'b' }]),
      invitations: signal<unknown[]>([]),
    };
    session = {
      isPlatformAdmin: signal(false),
      userName: signal<string | null>('Ana Prueba'),
      logout: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [Shell, translocoTesting(), MatIconTestingModule],
      providers: [
        provideRouter([{ path: '', component: CountingPage }]),
        { provide: ActiveClinic, useValue: clinic },
        { provide: Session, useValue: session },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Shell);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function menu(root: HTMLElement): string[] {
    return [...root.querySelectorAll('nav a')].map((link) => link.textContent?.trim() ?? '');
  }

  it('renders the header, the navigation and the content area with translated texts', async () => {
    const root = await render();

    expect(root.querySelector('mat-toolbar')?.textContent).toContain(es.app.name);
    expect(root.querySelector('.shell__toggle')?.getAttribute('aria-label')).toBe(
      es.layout.toggleMenu,
    );
    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe(es.layout.mainNavigation);
    expect(root.querySelector('main router-outlet')).not.toBeNull();
  });

  it('tells assistive technology whether the menu is open and which page is current', async () => {
    const fixture = TestBed.createComponent(Shell);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector<HTMLButtonElement>('.shell__toggle');

    expect(root.querySelector('nav a')?.getAttribute('aria-current')).toBe('page');
    expect(toggle?.getAttribute('aria-expanded')).toBe('true');

    toggle?.click();
    await fixture.whenStable();

    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
  });

  it('shows the menu of a clinic role with the configuration', async () => {
    expect(menu(await render())).toEqual([es.layout.nav.home, es.layout.nav.configuration]);
  });

  it('shows the configuration once to a platform administrator who works in a clinic', async () => {
    session.isPlatformAdmin.set(true);

    expect(menu(await render())).toEqual([es.layout.nav.home, es.layout.nav.configuration]);
  });

  it('shows the active clinic, the user and a way to change clinic', async () => {
    const root = await render();

    expect(root.querySelector('.shell__clinic')?.textContent).toContain('Clínica Norte');
    expect(root.querySelector('.shell__user')?.textContent).toContain('Ana Prueba');
    const change = root.querySelector('.shell__change-clinic');
    expect(change?.textContent).toContain(es.layout.clinic.change);
    expect(change?.getAttribute('href')).toBe('/clinica');
  });

  it('offers no change with a single clinic and no invitations', async () => {
    clinic.clinics.set([{ clinicId: 'a' }]);

    expect((await render()).querySelector('.shell__change-clinic')).toBeNull();
  });

  it('logs out from the header', async () => {
    const root = await render();

    root.querySelector<HTMLButtonElement>('.shell__logout')?.click();

    expect(root.querySelector('.shell__logout')?.textContent).toContain(es.layout.logout);
    expect(session.logout).toHaveBeenCalledTimes(1);
  });

  it('creates the page again when the active clinic changes, so it reads the new clinic’s data', async () => {
    const fixture = TestBed.createComponent(Shell);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    expect(created).toBe(1);

    clinic.id.set('b');
    await fixture.whenStable();

    expect(created).toBe(2);
  });
});
