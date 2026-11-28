import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';

import { es, translocoTesting } from '../../../testing/i18n';
import { ApiConfiguration } from '../../api/api-configuration';
import { ClinicUserDetails } from '../../api/models/clinic-user-details';
import { PractitionerDetails } from '../../api/models/practitioner-details';
import { ConfigurationSections, SectionAccess } from '../configuration/configuration-sections';
import { PractitionersHome } from './practitioners-home';

const API = 'https://api.dentasoft.test';
const PRACTITIONERS = `${API}/api/v1/clinic/practitioner-roles`;
const USERS = `${API}/api/v1/clinic/users`;

const MARTA: PractitionerDetails = {
  id: 'p-1',
  userId: 'kc-marta',
  displayName: 'Dra. Marta López',
  specialty: 'ORTHODONTICS',
  licenseProvince: '28',
  licenseNumber: '28/12345',
  agendaColor: '#2A9D8F',
  status: 'ACTIVE',
  version: 3,
};
/** A hygienist with no licence and no user. */
const IRENE: PractitionerDetails = {
  id: 'p-2',
  displayName: 'Irene Sanz',
  specialty: 'DENTAL_HYGIENE',
  agendaColor: '#E76F51',
  status: 'ACTIVE',
  version: 0,
};
const FORMER: PractitionerDetails = {
  id: 'p-3',
  displayName: 'Dr. Pablo Gil',
  specialty: 'GENERAL_DENTISTRY',
  licenseProvince: '08',
  licenseNumber: '0812',
  agendaColor: '#264653',
  status: 'INACTIVE',
  version: 5,
};

function member(
  id: string,
  role: ClinicUserDetails['role'],
  firstName: string,
  extra: Partial<ClinicUserDetails> = {},
): ClinicUserDetails {
  return {
    id: `m-${id}`,
    userId: `kc-${id}`,
    role,
    active: true,
    invitationPending: false,
    version: 0,
    profile: { email: `${id}@clinica-norte.test`, firstName, lastName: 'Prueba' },
    ...extra,
  };
}

const USER_MARTA = member('marta', 'ODONTOLOGO', 'Marta');
const USER_JUAN = member('juan', 'ODONTOLOGO', 'Juan');
const USER_IRENE = member('irene', 'HIGIENISTA', 'Irene');
const USER_ADMIN = member('ana', 'ADMIN_CLINICA', 'Ana');
const USER_LUIS = member('luis', 'RECEPCION', 'Luis');
const USER_EVA = member('eva', 'ODONTOLOGO', 'Eva', { active: false });

function page<T>(content: T[], number = 0, totalElements = content.length, size = 20) {
  return {
    content,
    page: { number, size, totalElements, totalPages: Math.ceil(totalElements / size) },
  };
}

function problem(status: number, detail: string, errors?: { field: string; message: string }[]) {
  return [
    { title: 'No se puede hacer', detail, errors },
    { status, statusText: 'Error' },
  ] as const;
}

describe('PractitionersHome', () => {
  let fixture: ComponentFixture<PractitionersHome>;
  let http: HttpTestingController;
  let access: SectionAccess;

  beforeEach(() => {
    access = 'EDIT';
    TestBed.configureTestingModule({
      imports: [PractitionersHome, translocoTesting()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
        { provide: ConfigurationSections, useValue: { accessTo: () => access } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    http.verify();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
  }

  function matching(method: string, url: string): TestRequest[] {
    return http.match((req) => req.method === method && req.url === url);
  }

  async function expectRequest(method: string, url: string): Promise<TestRequest> {
    let found: TestRequest[] = [];
    await vi.waitFor(async () => {
      await fixture.whenStable();
      found = matching(method, url);
      expect(found.length).toBe(1);
    });
    return found[0];
  }

  /** Renders the screen with the list and, when editing, the clinic's users for the form. */
  async function show(
    content: PractitionerDetails[],
    users: ClinicUserDetails[] = [USER_MARTA, USER_JUAN, USER_IRENE],
    total = content.length,
  ): Promise<void> {
    fixture = TestBed.createComponent(PractitionersHome);
    fixture.detectChanges();
    (await expectRequest('GET', PRACTITIONERS)).flush(page(content, 0, total));
    if (access === 'EDIT') {
      (await expectRequest('GET', USERS)).flush(page(users, 0, users.length, 50));
    }
    await vi.waitFor(async () => {
      await settle();
      expect(root().textContent).not.toContain(es.practitioners.list.loading);
    });
  }

  function row(id: string): HTMLElement {
    return root().querySelector(`[data-practitioner="${id}"]`) as HTMLElement;
  }

  function button(scope: ParentNode, text: string): HTMLButtonElement | undefined {
    return [...scope.querySelectorAll('button')].find(
      (candidate) => candidate.textContent?.trim() === text,
    );
  }

  function click(scope: ParentNode, text: string): void {
    const found = button(scope, text);
    if (!found) {
      throw new Error(`No button ${text}`);
    }
    found.click();
  }

  async function dialogButton(selector: string): Promise<HTMLButtonElement> {
    let found: HTMLButtonElement | undefined;
    await vi.waitFor(() => {
      found = [...document.querySelectorAll<HTMLButtonElement>(selector)].at(-1);
      expect(found?.textContent?.trim()).toBeTruthy();
    });
    return found as HTMLButtonElement;
  }

  function outcome(): HTMLElement {
    return root().querySelector('.practitioners__outcome') as HTMLElement;
  }

  function form(): HTMLElement {
    return root().querySelector('app-practitioner-form') as HTMLElement;
  }

  function field(name: string): HTMLElement {
    return form().querySelector(`[formcontrolname="${name}"]`) as HTMLElement;
  }

  async function type(name: string, value: string): Promise<void> {
    const input = field(name) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    await settle();
  }

  /** The options of a Material select, which open in an overlay outside the component. */
  async function options(name: string): Promise<HTMLElement[]> {
    (field(name).querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
    await settle();
    let found: HTMLElement[] = [];
    await vi.waitFor(() => {
      found = [...document.querySelectorAll<HTMLElement>('mat-option')];
      expect(found.length).toBeGreaterThan(0);
    });
    return found;
  }

  async function choose(name: string, label: string): Promise<void> {
    const option = (await options(name)).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    if (!option) {
      throw new Error(`No option ${label}`);
    }
    option.click();
    await settle();
  }

  async function save(): Promise<void> {
    (form().querySelector('.form-actions__save') as HTMLButtonElement).click();
    await settle();
  }

  async function fillDentist(): Promise<void> {
    await type('displayName', 'Dr. Juan Prieto');
    await choose('specialty', es.practitioners.areas.GENERAL_DENTISTRY);
    await choose('licenseProvince', es.practitioners.provinces['41']);
    await type('licenseNumber', '41-ab77');
    await type('agendaColor', '#1d3557');
  }

  describe('list', () => {
    it('asks for the active ones first, in the backend order, and shows each one', async () => {
      fixture = TestBed.createComponent(PractitionersHome);
      fixture.detectChanges();
      const request = await expectRequest('GET', PRACTITIONERS);

      expect(request.request.params.get('status')).toBe('ACTIVE');
      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('20');
      expect(request.request.params.has('sort')).toBe(false);
      request.flush(page([MARTA, IRENE]));
      (await expectRequest('GET', USERS)).flush(page([], 0, 0, 50));
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-1')).not.toBeNull();
      });

      const region = root().querySelector('[role="region"]') as HTMLElement;
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(document.getElementById(region.getAttribute('aria-labelledby') ?? '')?.tagName).toBe(
        'CAPTION',
      );
      expect(row('p-1').querySelector('th')?.textContent?.trim()).toBe('Dra. Marta López');
      expect(row('p-1').textContent).toContain(es.practitioners.areas.ORTHODONTICS);
      expect(row('p-1').textContent).toContain('28/12345');
      expect(row('p-1').textContent).toContain(es.practitioners.provinces['28']);
      // The colour is written, not only shown.
      expect(row('p-1').textContent).toContain('#2A9D8F');
      expect(row('p-1').textContent).toContain(es.practitioners.status.ACTIVE);
      expect(row('p-2').textContent).toContain(es.practitioners.list.noLicense);
      expect(row('p-2').textContent).toContain(es.practitioners.areas.DENTAL_HYGIENE);
    });

    it('never says «especialidad»', () => {
      expect(JSON.stringify(es.practitioners).toLowerCase()).not.toContain('especialidad');
    });

    it('shows the inactive ones when asked, from the first page', async () => {
      await show([MARTA], [], 25);

      (root().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
      const second = await expectRequest('GET', PRACTITIONERS);
      expect(second.request.params.get('page')).toBe('1');
      expect(second.request.params.get('status')).toBe('ACTIVE');
      second.flush(page([IRENE], 1, 25));
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-2')).not.toBeNull();
      });
      expect(root().textContent).toContain('21–25 de 25');

      click(root(), es.practitioners.filter.INACTIVE);
      const inactive = await expectRequest('GET', PRACTITIONERS);

      expect(inactive.request.params.get('status')).toBe('INACTIVE');
      expect(inactive.request.params.get('page')).toBe('0');
      inactive.flush(page([FORMER]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-3')).not.toBeNull();
      });
      expect(row('p-3').textContent).toContain(es.practitioners.status.INACTIVE);
    });

    it('says when the list could not be read and tries again', async () => {
      fixture = TestBed.createComponent(PractitionersHome);
      fixture.detectChanges();
      (await expectRequest('GET', PRACTITIONERS)).flush(null, {
        status: 500,
        statusText: 'Error',
      });
      (await expectRequest('GET', USERS)).flush(page([], 0, 0, 50));
      await vi.waitFor(async () => {
        await settle();
        expect(root().textContent).toContain(es.practitioners.list.loadError);
      });

      click(root(), es.practitioners.list.retry);
      (await expectRequest('GET', PRACTITIONERS)).flush(page([MARTA]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-1')).not.toBeNull();
      });
      expect(root().textContent).not.toContain(es.practitioners.list.loadError);
    });

    it('says when there is nobody to show', async () => {
      await show([]);

      expect(root().textContent).toContain(es.practitioners.list.empty);
    });
  });

  describe('view only', () => {
    it('shows the list with no form or actions and never reads the users', async () => {
      access = 'VIEW';
      await show([MARTA, FORMER]);

      expect(row('p-1')).not.toBeNull();
      expect(form()).toBeNull();
      expect(root().querySelector('form')).toBeNull();
      expect(button(row('p-1'), es.practitioners.actions.edit)).toBeUndefined();
      expect(button(row('p-1'), es.practitioners.actions.deactivate)).toBeUndefined();
      expect(button(row('p-3'), es.practitioners.actions.activate)).toBeUndefined();
      expect(root().textContent).not.toContain(es.practitioners.list.actions);
      expect(matching('GET', USERS)).toEqual([]);
    });
  });

  describe('new practitioner', () => {
    it('offers the nine clinical areas', async () => {
      await show([]);

      expect(form().textContent).toContain(es.practitioners.form.newTitle);
      expect(await options('specialty')).toHaveLength(9);
    });

    it('lists the 52 provinces by name, after «sin colegiado»', async () => {
      await show([]);

      const provinces = await options('licenseProvince');

      expect(provinces).toHaveLength(53);
      expect(provinces[0].textContent?.trim()).toBe(es.practitioners.form.noLicense);
      expect(provinces[1].textContent?.trim()).toBe(es.practitioners.provinces['15']);
      expect(provinces.at(-1)?.textContent?.trim()).toBe(es.practitioners.provinces['50']);
    });

    it('creates a dentist with the colour in upper case, reloads the list and announces it', async () => {
      await show([MARTA]);
      await fillDentist();

      await save();
      const request = await expectRequest('POST', PRACTITIONERS);

      expect(request.request.body).toEqual({
        displayName: 'Dr. Juan Prieto',
        specialty: 'GENERAL_DENTISTRY',
        licenseProvince: '41',
        licenseNumber: '41-AB77',
        agendaColor: '#1D3557',
      });
      const juan: PractitionerDetails = {
        id: 'p-9',
        displayName: 'Dr. Juan Prieto',
        specialty: 'GENERAL_DENTISTRY',
        licenseProvince: '41',
        licenseNumber: '41-AB77',
        agendaColor: '#1D3557',
        status: 'ACTIVE',
        version: 0,
      };
      request.flush(juan);
      (await expectRequest('GET', PRACTITIONERS)).flush(page([juan, MARTA]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-9')).not.toBeNull();
      });
      expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
        'Dr. Juan Prieto se ha añadido a los profesionales.',
      );
      expect((field('displayName') as HTMLInputElement).value).toBe('');
    });

    it('creates a hygienist with no licence and links her user', async () => {
      await show([]);
      await type('displayName', 'Irene Sanz');
      await choose('specialty', es.practitioners.areas.DENTAL_HYGIENE);
      await choose('userId', 'Irene Prueba (Higiene dental)');

      await save();
      const request = await expectRequest('POST', PRACTITIONERS);

      expect(request.request.body).toEqual({
        userId: 'kc-irene',
        displayName: 'Irene Sanz',
        specialty: 'DENTAL_HYGIENE',
        agendaColor: expect.stringMatching(/^#[0-9A-F]{6}$/),
      });
      request.flush({ ...IRENE, userId: 'kc-irene' });
      (await expectRequest('GET', PRACTITIONERS)).flush(page([IRENE]));
      await settle();
    });

    it('refuses a name with no letters without asking the server', async () => {
      await show([]);
      await fillDentist();
      await type('displayName', '12 34');

      await save();

      expect(form().textContent).toContain(es.forms.errors.personName);
      expect(form().textContent).toContain(es.forms.invalid);
      expect(document.activeElement).toBe(field('displayName'));
      expect(matching('POST', PRACTITIONERS)).toEqual([]);
    });

    it('refuses a name with invisible characters', async () => {
      await show([]);
      await fillDentist();
      await type('displayName', 'Juan​ Prieto');

      await save();

      expect(form().textContent).toContain(es.forms.errors.personName);
      expect(matching('POST', PRACTITIONERS)).toEqual([]);
    });

    it('refuses a licence number with the wrong format', async () => {
      await show([]);
      await fillDentist();
      await type('licenseNumber', '41--77');

      await save();

      expect(form().textContent).toContain(es.practitioners.form.errors.licenseNumber);
      expect(document.activeElement).toBe(field('licenseNumber'));
      expect(matching('POST', PRACTITIONERS)).toEqual([]);

      await type('licenseNumber', '12');
      await save();
      expect(form().textContent).toContain(es.practitioners.form.errors.licenseNumber);
      expect(matching('POST', PRACTITIONERS)).toEqual([]);
    });

    it('asks for the number when a province is chosen', async () => {
      await show([]);
      await fillDentist();
      await type('licenseNumber', '');

      await save();

      expect(form().querySelector('.practitioner-form__license-error')?.textContent).toContain(
        es.practitioners.form.errors.licenseComplete,
      );
      expect(document.activeElement).toBe(field('licenseNumber'));
      expect(matching('POST', PRACTITIONERS)).toEqual([]);
    });

    it('asks for a licence unless the area is dental hygiene', async () => {
      await show([]);
      await type('displayName', 'Dr. Juan Prieto');
      await choose('specialty', es.practitioners.areas.PERIODONTICS);

      await save();

      expect(form().querySelector('.practitioner-form__license-error')?.textContent).toContain(
        es.practitioners.form.errors.licenseGiven,
      );
      expect(document.activeElement).toBe(field('licenseProvince'));
      expect(matching('POST', PRACTITIONERS)).toEqual([]);

      await choose('specialty', es.practitioners.areas.DENTAL_HYGIENE);
      expect(form().querySelector('.practitioner-form__license-error')?.textContent?.trim()).toBe(
        '',
      );
      await save();
      (await expectRequest('POST', PRACTITIONERS)).flush(IRENE);
      (await expectRequest('GET', PRACTITIONERS)).flush(page([IRENE]));
      await settle();
    });

    it('puts the field errors of the server on their fields', async () => {
      await show([]);
      await fillDentist();

      await save();
      (await expectRequest('POST', PRACTITIONERS)).flush(
        ...problem(400, 'Datos no válidos', [
          { field: 'licenseNumber', message: 'el tamaño debe estar entre 3 y 12' },
        ]),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(field('licenseNumber').closest('mat-form-field')?.textContent).toContain(
          'el tamaño debe estar entre 3 y 12',
        );
      });
      expect(document.activeElement).toBe(field('licenseNumber'));
    });

    it('shows the explanation of a conflict', async () => {
      await show([]);
      await fillDentist();

      await save();
      (await expectRequest('POST', PRACTITIONERS)).flush(
        ...problem(
          409,
          'Ya hay otro profesional activo con ese número de colegiado en la misma provincia.',
        ),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'Ya hay otro profesional activo con ese número de colegiado en la misma provincia.',
        );
      });
    });
  });

  describe('user link', () => {
    it('offers only the active odontologists and hygienists, pending invitations included', async () => {
      const pending = member('nuevo', 'ODONTOLOGO', 'Nuevo', { invitationPending: true });
      await show([], [USER_MARTA, USER_ADMIN, USER_LUIS, USER_EVA, USER_IRENE, pending]);

      const labels = (await options('userId')).map((option) => option.textContent?.trim());

      expect(labels).toEqual([
        es.practitioners.form.noUser,
        'Marta Prueba (Odontología)',
        'Irene Prueba (Higiene dental)',
        'Nuevo Prueba (Odontología)',
      ]);
    });

    it('reads every page of users', async () => {
      fixture = TestBed.createComponent(PractitionersHome);
      fixture.detectChanges();
      (await expectRequest('GET', PRACTITIONERS)).flush(page([]));
      const first = await expectRequest('GET', USERS);
      expect(first.request.params.get('size')).toBe('50');
      expect(first.request.params.get('page')).toBe('0');
      first.flush(page([USER_ADMIN], 0, 51, 50));
      const second = await expectRequest('GET', USERS);
      expect(second.request.params.get('page')).toBe('1');
      second.flush(page([USER_JUAN], 1, 51, 50));
      await settle();

      const labels = (await options('userId')).map((option) => option.textContent?.trim());
      expect(labels).toEqual([es.practitioners.form.noUser, 'Juan Prueba (Odontología)']);
    });
  });

  describe('edit', () => {
    it('fills the form, sends the version and updates the row', async () => {
      await show([MARTA, IRENE]);

      click(row('p-2'), es.practitioners.actions.edit);
      await settle();

      expect(form().textContent).toContain('Editar: Irene Sanz');
      expect((field('displayName') as HTMLInputElement).value).toBe('Irene Sanz');
      expect(document.activeElement).toBe(field('displayName'));
      await type('displayName', 'Irene Sanz Ruiz');
      await save();
      const request = await expectRequest('PUT', `${PRACTITIONERS}/p-2`);

      expect(request.request.body).toEqual({
        displayName: 'Irene Sanz Ruiz',
        specialty: 'DENTAL_HYGIENE',
        agendaColor: '#E76F51',
        version: 0,
      });
      request.flush({ ...IRENE, displayName: 'Irene Sanz Ruiz', version: 1 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-2').querySelector('th')?.textContent?.trim()).toBe('Irene Sanz Ruiz');
      });
      expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
        'Datos de Irene Sanz Ruiz guardados.',
      );
      expect(form().textContent).toContain(es.practitioners.form.newTitle);
    });

    it('keeps the linked user and does not let it change', async () => {
      await show([MARTA]);

      click(row('p-1'), es.practitioners.actions.edit);
      await settle();

      const select = field('userId');
      expect(select.getAttribute('aria-disabled')).toBe('true');
      expect(select.textContent).toContain('Marta Prueba (Odontología)');
      expect(form().textContent).toContain(es.practitioners.form.userFixed);
      await type('agendaColor', '#aabbcc');
      await save();
      const request = await expectRequest('PUT', `${PRACTITIONERS}/p-1`);

      expect(request.request.body).toEqual({
        userId: 'kc-marta',
        displayName: 'Dra. Marta López',
        specialty: 'ORTHODONTICS',
        licenseProvince: '28',
        licenseNumber: '28/12345',
        agendaColor: '#AABBCC',
        version: 3,
      });
      request.flush({ ...MARTA, agendaColor: '#AABBCC', version: 4 });
      await settle();
    });

    it('shows a version conflict and keeps what was typed', async () => {
      await show([MARTA]);

      click(row('p-1'), es.practitioners.actions.edit);
      await settle();
      await type('displayName', 'Dra. Marta López Gil');
      await save();
      (await expectRequest('PUT', `${PRACTITIONERS}/p-1`)).flush(
        ...problem(
          409,
          'Alguien ha cambiado estos datos mientras los editabas. Vuelve a cargarlos e inténtalo de nuevo.',
        ),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'Alguien ha cambiado estos datos mientras los editabas.',
        );
      });
      expect((field('displayName') as HTMLInputElement).value).toBe('Dra. Marta López Gil');
    });

    it('goes back to a new practitioner when the edit is cancelled', async () => {
      await show([MARTA]);

      click(row('p-1'), es.practitioners.actions.edit);
      await settle();
      click(form(), es.practitioners.form.cancelEdit);
      await settle();

      expect(form().textContent).toContain(es.practitioners.form.newTitle);
      expect((field('displayName') as HTMLInputElement).value).toBe('');
      expect(matching('PUT', `${PRACTITIONERS}/p-1`)).toEqual([]);
    });
  });

  describe('deactivation and activation', () => {
    it('asks before deactivating and sends nothing if the user cancels', async () => {
      await show([MARTA]);

      click(row('p-1'), es.practitioners.actions.deactivate);
      const cancel = await dialogButton('.confirm-dialog__cancel');
      expect(document.querySelector('mat-dialog-content')?.textContent).toContain(
        'Dra. Marta López',
      );
      cancel.click();
      await settle();

      expect(matching('POST', `${PRACTITIONERS}/p-1/deactivation`)).toEqual([]);
    });

    it('deactivates after confirming, with the version', async () => {
      await show([MARTA]);

      click(row('p-1'), es.practitioners.actions.deactivate);
      (await dialogButton('.confirm-dialog__confirm')).click();
      const request = await expectRequest('POST', `${PRACTITIONERS}/p-1/deactivation`);

      expect(request.request.body).toEqual({ version: 3 });
      request.flush({ ...MARTA, status: 'INACTIVE', version: 4 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-1').textContent).toContain(es.practitioners.status.INACTIVE);
      });
      expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
        'Dra. Marta López ya no aparece para citas nuevas.',
      );
      expect(button(row('p-1'), es.practitioners.actions.deactivate)).toBeUndefined();
      expect(button(row('p-1'), es.practitioners.actions.activate)).toBeTruthy();
    });

    it('activates with the version and shows why it was refused', async () => {
      await show([FORMER]);

      click(row('p-3'), es.practitioners.actions.activate);
      const request = await expectRequest('POST', `${PRACTITIONERS}/p-3/activation`);
      expect(request.request.body).toEqual({ version: 5 });
      request.flush(
        ...problem(
          409,
          'Ya hay otro profesional activo con ese número de colegiado en la misma provincia.',
        ),
      );
      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="alert"]')?.textContent).toContain(
          'Ya hay otro profesional activo con ese número de colegiado en la misma provincia.',
        );
      });

      click(row('p-3'), es.practitioners.actions.activate);
      (await expectRequest('POST', `${PRACTITIONERS}/p-3/activation`)).flush({
        ...FORMER,
        status: 'ACTIVE',
        version: 6,
      });
      await vi.waitFor(async () => {
        await settle();
        expect(row('p-3').textContent).toContain(es.practitioners.status.ACTIVE);
      });
      expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
        'Dr. Pablo Gil vuelve a estar disponible para citas nuevas.',
      );
    });
  });
});
