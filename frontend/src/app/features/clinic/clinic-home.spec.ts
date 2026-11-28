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
import { ClinicConfigurationDetails } from '../../api/models/clinic-configuration-details';
import { LocationDetails } from '../../api/models/location-details';
import { SettingsDetails } from '../../api/models/settings-details';
import { Slot } from '../../api/models/slot';
import { ActiveClinic } from '../../core/clinic/active-clinic';
import { ConfigurationSections, SectionAccess } from '../configuration/configuration-sections';
import { ClinicHome } from './clinic-home';

const API = 'https://api.dentasoft.test';
const CLINIC = `${API}/api/v1/clinic`;
const SETTINGS = `${API}/api/v1/clinic/settings`;
const HOURS = `${API}/api/v1/clinic/hours-of-operation`;
const LOCATIONS = `${API}/api/v1/clinic/locations`;

const DETAILS: ClinicConfigurationDetails = {
  id: 'c-1',
  name: 'Clínica Prueba Norte',
  legalName: 'Clínica Prueba Norte S.L.',
  taxId: 'B00000000',
  timezone: 'Europe/Madrid',
  locale: 'es-ES',
  version: 2,
};
const OPTIONS: SettingsDetails = {
  defaultAppointmentMinutes: 30,
  contactEmail: 'recepcion@clinica-norte.test',
  contactPhone: '+34 910 000 000',
  version: 1,
};
/** Monday morning and afternoon, sent by the backend out of order, and Tuesday morning. */
const WEEK: Slot[] = [
  { dayOfWeek: 'MONDAY', openingTime: '16:00:00', closingTime: '20:00:00' },
  { dayOfWeek: 'MONDAY', openingTime: '09:00:00', closingTime: '14:00:00' },
  { dayOfWeek: 'TUESDAY', openingTime: '09:00:00', closingTime: '14:00:00' },
];
const ROOM_1: LocationDetails = {
  id: 'l-1',
  name: 'Gabinete 1',
  color: '#1E88E5',
  status: 'ACTIVE',
  version: 0,
};
const ROOM_2: LocationDetails = { id: 'l-2', name: 'Gabinete 2', status: 'ACTIVE', version: 3 };

/** The zones of the browser in these tests, out of order. */
const ZONES = ['Europe/Madrid', 'America/New_York', 'Atlantic/Canary'];

function page<T>(content: T[], number = 0, totalElements = content.length, size = 20) {
  return {
    content,
    page: { number, size, totalElements, totalPages: Math.ceil(totalElements / size) },
  };
}

function problem(status: number, title: string, detail: string, errors?: object[]) {
  return [
    { title, detail, errors },
    { status, statusText: 'Error' },
  ] as const;
}

describe('ClinicHome', () => {
  let fixture: ComponentFixture<ClinicHome>;
  let http: HttpTestingController;
  let access: SectionAccess;

  beforeEach(() => {
    access = 'EDIT';
    vi.spyOn(Intl, 'supportedValuesOf').mockReturnValue(ZONES);
    TestBed.configureTestingModule({
      imports: [ClinicHome, translocoTesting()],
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
    vi.restoreAllMocks();
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

  /** Renders the section with each block's data. */
  async function show(rooms: LocationDetails[] = [ROOM_1, ROOM_2]): Promise<void> {
    fixture = TestBed.createComponent(ClinicHome);
    fixture.detectChanges();
    (await expectRequest('GET', CLINIC)).flush(DETAILS);
    (await expectRequest('GET', SETTINGS)).flush(OPTIONS);
    (await expectRequest('GET', HOURS)).flush(WEEK);
    (await expectRequest('GET', LOCATIONS)).flush(page(rooms));
    await vi.waitFor(async () => {
      await settle();
      expect(root().querySelector('mat-spinner')).toBeNull();
    });
  }

  function block(selector: string): HTMLElement {
    return root().querySelector(selector) as HTMLElement;
  }

  const data = () => block('app-clinic-data-form');
  const settingsBlock = () => block('app-clinic-settings-form');
  const hours = () => block('app-opening-hours-form');
  const rooms = () => block('app-clinic-locations');

  function field(scope: HTMLElement, name: string): HTMLInputElement {
    return scope.querySelector(`[formcontrolname="${name}"]`) as HTMLInputElement;
  }

  async function type(input: HTMLInputElement, value: string): Promise<void> {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    await settle();
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

  async function save(scope: HTMLElement): Promise<void> {
    (scope.querySelector('.form-actions__save') as HTMLButtonElement).click();
    await settle();
  }

  function errorsOf(scope: HTMLElement): string {
    return [...scope.querySelectorAll('mat-error, .clinic-block__error')]
      .map((error) => error.textContent?.trim())
      .join(' | ');
  }

  async function dialogButton(selector: string): Promise<HTMLButtonElement> {
    let found: HTMLButtonElement | undefined;
    await vi.waitFor(() => {
      found = [...document.querySelectorAll<HTMLButtonElement>(selector)].at(-1);
      expect(found?.textContent?.trim()).toBeTruthy();
    });
    return found as HTMLButtonElement;
  }

  function row(id: string): HTMLElement {
    return rooms().querySelector(`[data-location="${id}"]`) as HTMLElement;
  }

  describe('clinic data', () => {
    it('edits the name and shows the fiscal data without a field for them', async () => {
      await show();

      expect(field(data(), 'name').value).toBe('Clínica Prueba Norte');
      const fiscal = data().querySelector('.clinic-block__fiscal') as HTMLElement;
      expect(fiscal.textContent).toContain('Clínica Prueba Norte S.L.');
      expect(fiscal.textContent).toContain('B00000000');
      expect(data().textContent).toContain(es.clinic.data.fiscalHint);
      expect(field(data(), 'legalName')).toBeNull();
      expect(field(data(), 'taxId')).toBeNull();
      expect(data().querySelectorAll('input').length).toBe(2);
    });

    it('saves the name and time zone with the version it read', async () => {
      await show();
      const renamed = vi.spyOn(TestBed.inject(ActiveClinic), 'renamed');

      await type(field(data(), 'name'), '  Clínica Prueba Centro ');
      await save(data());
      const request = await expectRequest('PUT', CLINIC);

      expect(request.request.body).toEqual({
        name: 'Clínica Prueba Centro',
        timezone: 'Europe/Madrid',
        version: 2,
      });
      request.flush({ ...DETAILS, name: 'Clínica Prueba Centro', version: 3 });
      await vi.waitFor(async () => {
        await settle();
        expect(data().querySelector('[role="status"]')?.textContent).toContain(
          es.clinic.data.saved,
        );
      });
      expect(field(data(), 'name').value).toBe('Clínica Prueba Centro');
      // The header shows the new name at once.
      expect(renamed).toHaveBeenCalledWith('c-1', 'Clínica Prueba Centro');
    });

    it('offers the zones of the browser, filtered as the user types', async () => {
      await show();
      const zone = field(data(), 'timezone');

      zone.dispatchEvent(new Event('focusin'));
      await type(zone, 'new york');
      let shown: string[] = [];
      await vi.waitFor(() => {
        shown = [...document.querySelectorAll('mat-option')].map((o) => o.textContent!.trim());
        expect(shown.length).toBeGreaterThan(0);
      });

      expect(Intl.supportedValuesOf).toHaveBeenCalledWith('timeZone');
      expect(shown).toEqual(['America/New_York']);
    });

    it('sends the zone chosen from the list', async () => {
      await show();
      const zone = field(data(), 'timezone');

      zone.dispatchEvent(new Event('focusin'));
      await type(zone, 'canary');
      let option: HTMLElement | undefined;
      await vi.waitFor(() => {
        option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
          (o) => o.textContent?.trim() === 'Atlantic/Canary',
        );
        expect(option).toBeTruthy();
      });
      option!.click();
      await settle();
      await save(data());
      const request = await expectRequest('PUT', CLINIC);

      expect(request.request.body.timezone).toBe('Atlantic/Canary');
      request.flush({ ...DETAILS, timezone: 'Atlantic/Canary', version: 3 });
    });

    it('refuses a zone that is not in the list without sending anything', async () => {
      await show();

      await type(field(data(), 'timezone'), 'Mars/Olympus');
      await save(data());

      expect(matching('PUT', CLINIC)).toEqual([]);
      expect(errorsOf(data())).toContain(es.clinic.errors.timeZone);
      expect(document.activeElement).toBe(field(data(), 'timezone'));
    });

    it('keeps the clinic zone even if this browser does not list it', async () => {
      vi.mocked(Intl.supportedValuesOf).mockReturnValue(['America/New_York']);
      await show();

      await type(field(data(), 'name'), 'Clínica Prueba Sur');
      await save(data());
      const request = await expectRequest('PUT', CLINIC);

      expect(request.request.body.timezone).toBe('Europe/Madrid');
      request.flush(DETAILS);
    });

    it('puts the server validation messages on their fields', async () => {
      await show();

      await type(field(data(), 'name'), 'Clínica Prueba Centro');
      await save(data());
      (await expectRequest('PUT', CLINIC)).flush(
        ...problem(400, 'Datos no válidos', 'Revisa los campos.', [
          { field: 'name', message: 'el nombre no es válido' },
        ]),
      );
      await vi.waitFor(async () => {
        await settle();
        expect(errorsOf(data())).toContain('el nombre no es válido');
      });
      expect(data().querySelector('app-failure-message')?.textContent).toContain(es.forms.invalid);
    });

    it('shows a version conflict in the form', async () => {
      await show();

      await type(field(data(), 'name'), 'Clínica Prueba Centro');
      await save(data());
      (await expectRequest('PUT', CLINIC)).flush(
        ...problem(
          409,
          'Datos modificados por otra persona',
          'Alguien ha cambiado estos datos mientras los editabas.',
        ),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(data().querySelector('form app-failure-message')?.textContent).toContain(
          'Datos modificados por otra persona',
        );
      });
    });

    it('refuses an empty name or one with control characters', async () => {
      await show();

      await type(field(data(), 'name'), 'Clínica\u0007');
      await save(data());
      expect(errorsOf(data())).toContain(es.clinic.errors.plainText);

      await type(field(data(), 'name'), '');
      await save(data());
      expect(errorsOf(data())).toContain(es.forms.errors.required);
      expect(matching('PUT', CLINIC)).toEqual([]);
    });
  });

  describe('settings', () => {
    it('saves the duration and the contact data with the version', async () => {
      await show();

      await type(field(settingsBlock(), 'defaultAppointmentMinutes'), '45');
      await type(field(settingsBlock(), 'contactPhone'), '');
      await save(settingsBlock());
      const request = await expectRequest('PUT', SETTINGS);

      expect(request.request.body).toEqual({
        defaultAppointmentMinutes: 45,
        contactEmail: 'recepcion@clinica-norte.test',
        contactPhone: '',
        version: 1,
      });
      request.flush({ ...OPTIONS, defaultAppointmentMinutes: 45, contactPhone: undefined });
      await vi.waitFor(async () => {
        await settle();
        expect(settingsBlock().querySelector('[role="status"]')?.textContent).toContain(
          es.clinic.settings.saved,
        );
      });
    });

    it('checks the duration, the email and the phone as the backend does', async () => {
      await show();

      await type(field(settingsBlock(), 'defaultAppointmentMinutes'), '481');
      await type(field(settingsBlock(), 'contactEmail'), 'recepcion');
      await type(field(settingsBlock(), 'contactPhone'), '910-000');
      await save(settingsBlock());

      expect(matching('PUT', SETTINGS)).toEqual([]);
      const errors = errorsOf(settingsBlock());
      expect(errors).toContain('Escribe un número entero de minutos entre 5 y 480.');
      expect(errors).toContain(es.forms.errors.email);
      expect(errors).toContain(es.clinic.errors.contactPhone);
    });
  });

  describe('opening hours', () => {
    it('shows each day with its slots in order, and the days without them as closed', async () => {
      await show();

      const monday = hours().querySelector('[data-day="MONDAY"]') as HTMLElement;
      const times = [...monday.querySelectorAll('input')].map((input) => input.value);
      expect(times).toEqual(['09:00', '14:00', '16:00', '20:00']);
      const sunday = hours().querySelector('[data-day="SUNDAY"]') as HTMLElement;
      expect(sunday.textContent).toContain(es.clinic.hours.closed);
    });

    it('adds a slot and saves the whole week, day by day', async () => {
      await show();

      const friday = hours().querySelector('[data-day="FRIDAY"]') as HTMLElement;
      click(friday, es.clinic.hours.addSlot);
      await settle();
      const [opening, closing] = [...friday.querySelectorAll('input')];
      expect(document.activeElement).toBe(opening);
      await type(opening, '10:00');
      await type(closing, '13:30');
      await save(hours());
      const request = await expectRequest('PUT', HOURS);

      expect(request.request.body).toEqual({
        slots: [
          { dayOfWeek: 'MONDAY', openingTime: '09:00', closingTime: '14:00' },
          { dayOfWeek: 'MONDAY', openingTime: '16:00', closingTime: '20:00' },
          { dayOfWeek: 'TUESDAY', openingTime: '09:00', closingTime: '14:00' },
          { dayOfWeek: 'FRIDAY', openingTime: '10:00', closingTime: '13:30' },
        ],
      });
      request.flush([
        ...WEEK,
        { dayOfWeek: 'FRIDAY', openingTime: '10:00:00', closingTime: '13:30:00' },
      ]);
      await vi.waitFor(async () => {
        await settle();
        expect(hours().querySelector('[role="status"]')?.textContent).toContain(
          es.clinic.hours.saved,
        );
      });
    });

    it('removes a slot, so the day may end up closed', async () => {
      await show();

      const tuesday = hours().querySelector('[data-day="TUESDAY"]') as HTMLElement;
      click(tuesday, es.clinic.hours.removeSlot);
      await settle();
      expect(tuesday.textContent).toContain(es.clinic.hours.closed);
      expect(document.activeElement).toBe(button(tuesday, es.clinic.hours.addSlot));

      await save(hours());
      const request = await expectRequest('PUT', HOURS);
      expect(request.request.body.slots).toHaveLength(2);
      request.flush(WEEK.slice(0, 2));
    });

    it('refuses overlapping slots and a slot that closes before it opens', async () => {
      await show();

      const monday = hours().querySelector('[data-day="MONDAY"]') as HTMLElement;
      const inputs = [...monday.querySelectorAll('input')];
      await type(inputs[2], '13:00');
      const tuesday = hours().querySelector('[data-day="TUESDAY"]') as HTMLElement;
      await type(tuesday.querySelectorAll('input')[1], '08:00');
      await save(hours());

      expect(matching('PUT', HOURS)).toEqual([]);
      expect(monday.textContent).toContain(es.clinic.hours.errors.overlapping);
      expect(tuesday.textContent).toContain(es.clinic.hours.errors.closesBeforeOpening);
      expect(document.activeElement).toBe(inputs[0]);
    });

    it('lets a slot open when the previous one closes', async () => {
      await show();

      const monday = hours().querySelector('[data-day="MONDAY"]') as HTMLElement;
      await type(monday.querySelectorAll('input')[2], '14:00');
      await save(hours());

      const request = await expectRequest('PUT', HOURS);
      expect(request.request.body.slots[1]).toEqual({
        dayOfWeek: 'MONDAY',
        openingTime: '14:00',
        closingTime: '20:00',
      });
      request.flush(WEEK);
    });

    it('shows why the backend refused the week', async () => {
      await show();

      await type(hours().querySelectorAll('input')[1], '13:00');
      await save(hours());
      (await expectRequest('PUT', HOURS)).flush(
        ...problem(400, 'Horario no válido', 'Los tramos de un mismo día no pueden solaparse.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(hours().querySelector('app-failure-message')?.textContent).toContain(
          'Horario no válido',
        );
      });
    });
  });

  describe('treatment rooms', () => {
    it('lists the active rooms, with their colour written out', async () => {
      await show();

      expect(row('l-1').textContent).toContain('Gabinete 1');
      expect(row('l-1').textContent).toContain('#1E88E5');
      expect(row('l-2').textContent).toContain(es.clinic.locations.list.noColor);
    });

    it('asks for the active ones first, in the backend order', async () => {
      fixture = TestBed.createComponent(ClinicHome);
      fixture.detectChanges();
      const request = await expectRequest('GET', LOCATIONS);

      expect(request.request.params.get('status')).toBe('ACTIVE');
      expect(request.request.params.has('sort')).toBe(false);
      request.flush(page([ROOM_1]));
      for (const url of [CLINIC, SETTINGS]) {
        (await expectRequest('GET', url)).flush(url === CLINIC ? DETAILS : OPTIONS);
      }
      (await expectRequest('GET', HOURS)).flush(WEEK);
    });

    it('adds a room and reads the page again', async () => {
      await show();

      const form = rooms().querySelector('app-location-form') as HTMLElement;
      await type(field(form, 'name'), ' Gabinete 3 ');
      await type(field(form, 'color'), '#2a9d8f');
      await save(form);
      const request = await expectRequest('POST', LOCATIONS);

      expect(request.request.body).toEqual({ name: 'Gabinete 3', color: '#2A9D8F' });
      request.flush({ id: 'l-3', name: 'Gabinete 3', color: '#2A9D8F', status: 'ACTIVE' });
      (await expectRequest('GET', LOCATIONS)).flush(
        page([ROOM_1, ROOM_2, { ...ROOM_1, id: 'l-3', name: 'Gabinete 3' }]),
      );
      await vi.waitFor(async () => {
        await settle();
        expect(rooms().querySelector('.clinic-locations__outcome')?.textContent).toContain(
          'Gabinete 3 se ha añadido a los gabinetes.',
        );
      });
      expect(field(form, 'name').value).toBe('');
    });

    it('adds a room with Enter, and leaves the empty form without errors', async () => {
      await show();

      const form = rooms().querySelector('app-location-form') as HTMLElement;
      await type(field(form, 'name'), 'Gabinete 3');
      form.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
      (await expectRequest('POST', LOCATIONS)).flush({
        id: 'l-3',
        name: 'Gabinete 3',
        color: '#1E88E5',
        status: 'ACTIVE',
      });
      (await expectRequest('GET', LOCATIONS)).flush(page([ROOM_1, ROOM_2]));

      await vi.waitFor(async () => {
        await settle();
        expect(field(form, 'name').value).toBe('');
      });
      expect(errorsOf(form)).toBe('');
      expect(field(form, 'name').getAttribute('aria-invalid')).not.toBe('true');
    });

    it('saves an edited room with Enter, and the new-room form shows no errors', async () => {
      await show();

      click(row('l-2'), es.clinic.locations.actions.edit);
      await settle();
      const form = rooms().querySelector('app-location-form') as HTMLElement;
      await type(field(form, 'name'), 'Gabinete Sur');
      form.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
      (await expectRequest('PUT', `${LOCATIONS}/l-2`)).flush({
        ...ROOM_2,
        name: 'Gabinete Sur',
        color: '#1E88E5',
        version: 4,
      });

      await vi.waitFor(async () => {
        await settle();
        expect(form.textContent).toContain(es.clinic.locations.form.newTitle);
      });
      expect(field(form, 'name').value).toBe('');
      expect(errorsOf(form)).toBe('');
    });

    it('shows a repeated name in the form', async () => {
      await show();

      const form = rooms().querySelector('app-location-form') as HTMLElement;
      await type(field(form, 'name'), 'gabinete 1');
      await save(form);
      (await expectRequest('POST', LOCATIONS)).flush(
        ...problem(
          409,
          'Nombre de gabinete repetido',
          'Ya hay otro gabinete activo con ese nombre en la clínica.',
        ),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form.querySelector('app-failure-message')?.textContent).toContain(
          'Nombre de gabinete repetido',
        );
      });
      expect(field(form, 'name').value).toBe('gabinete 1');
    });

    it('edits a room with the version it read', async () => {
      await show();

      click(row('l-2'), es.clinic.locations.actions.edit);
      await settle();
      const form = rooms().querySelector('app-location-form') as HTMLElement;
      expect(form.textContent).toContain('Editar Gabinete 2');
      expect(document.activeElement).toBe(field(form, 'name'));
      await type(field(form, 'name'), 'Gabinete Sur');
      await save(form);
      const request = await expectRequest('PUT', `${LOCATIONS}/l-2`);

      expect(request.request.body).toEqual({ name: 'Gabinete Sur', color: '#1E88E5', version: 3 });
      request.flush({ ...ROOM_2, name: 'Gabinete Sur', color: '#1E88E5', version: 4 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('l-2').textContent).toContain('Gabinete Sur');
      });
    });

    it('deactivates a room after confirming, and reactivates it', async () => {
      await show();

      click(row('l-1'), es.clinic.locations.actions.deactivate);
      const confirm = await dialogButton('.confirm-dialog__confirm');
      expect(document.querySelector('mat-dialog-content')?.textContent).toContain(
        'Gabinete 1 dejará de ofrecerse para citas nuevas.',
      );
      confirm.click();
      const deactivation = await expectRequest('POST', `${LOCATIONS}/l-1/deactivation`);
      expect(deactivation.request.body).toEqual({ version: 0 });
      deactivation.flush({ ...ROOM_1, status: 'INACTIVE', version: 1 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('l-1').textContent).toContain(es.clinic.locations.status.INACTIVE);
      });

      click(row('l-1'), es.clinic.locations.actions.activate);
      const activation = await expectRequest('POST', `${LOCATIONS}/l-1/activation`);
      expect(activation.request.body).toEqual({ version: 1 });
      activation.flush({ ...ROOM_1, status: 'ACTIVE', version: 2 });
      await vi.waitFor(async () => {
        await settle();
        expect(rooms().querySelector('.clinic-locations__outcome')?.textContent).toContain(
          'Gabinete 1 vuelve a ofrecerse para citas nuevas.',
        );
      });
    });

    it('keeps what the user typed when the room open in the form changes status', async () => {
      await show();

      click(row('l-1'), es.clinic.locations.actions.edit);
      await settle();
      const form = rooms().querySelector('app-location-form') as HTMLElement;
      await type(field(form, 'name'), 'Gabinete Norte');
      click(row('l-1'), es.clinic.locations.actions.deactivate);
      (await dialogButton('.confirm-dialog__confirm')).click();
      (await expectRequest('POST', `${LOCATIONS}/l-1/deactivation`)).flush({
        ...ROOM_1,
        status: 'INACTIVE',
        version: 1,
      });
      await vi.waitFor(async () => {
        await settle();
        expect(row('l-1').textContent).toContain(es.clinic.locations.status.INACTIVE);
      });

      expect(field(form, 'name').value).toBe('Gabinete Norte');
      await save(form);
      const request = await expectRequest('PUT', `${LOCATIONS}/l-1`);
      expect(request.request.body).toEqual({
        name: 'Gabinete Norte',
        color: '#1E88E5',
        version: 1,
      });
      request.flush({ ...ROOM_1, name: 'Gabinete Norte', status: 'INACTIVE', version: 2 });
    });

    it('sends nothing if the user cancels the deactivation', async () => {
      await show();

      click(row('l-1'), es.clinic.locations.actions.deactivate);
      (await dialogButton('.confirm-dialog__cancel')).click();
      await settle();

      expect(matching('POST', `${LOCATIONS}/l-1/deactivation`)).toEqual([]);
    });
  });

  describe('for the roles that only see the section', () => {
    beforeEach(() => {
      access = 'VIEW';
    });

    it('shows every block as text, with no field and no action', async () => {
      await show();

      expect(
        root().querySelectorAll('form, input, app-form-actions, app-location-form'),
      ).toHaveLength(0);
      expect(data().textContent).toContain('Clínica Prueba Norte S.L.');
      expect(data().textContent).toContain('Europe/Madrid');
      expect(settingsBlock().textContent).toContain('30 minutos');
      expect(settingsBlock().textContent).toContain('+34 910 000 000');
      const monday = [...hours().querySelectorAll('tr')].find((tr) =>
        tr.textContent?.includes(es.clinic.hours.days.MONDAY),
      ) as HTMLElement;
      expect([...monday.querySelectorAll('li')].map((li) => li.textContent?.trim())).toEqual([
        'de 09:00 a 14:00',
        'de 16:00 a 20:00',
      ]);
      expect(hours().textContent).toContain(es.clinic.hours.closed);
      expect(row('l-1').textContent).toContain('Gabinete 1');
      expect(button(rooms(), es.clinic.locations.actions.edit)).toBeUndefined();
      expect(button(rooms(), es.clinic.locations.actions.deactivate)).toBeUndefined();
      expect(root().textContent).toContain(es.clinic.introViewOnly);
    });

    it('writes «not given» for the contact data the clinic left empty', async () => {
      fixture = TestBed.createComponent(ClinicHome);
      fixture.detectChanges();
      (await expectRequest('GET', CLINIC)).flush(DETAILS);
      (await expectRequest('GET', SETTINGS)).flush({ defaultAppointmentMinutes: 30, version: 0 });
      (await expectRequest('GET', HOURS)).flush([]);
      (await expectRequest('GET', LOCATIONS)).flush(page([]));
      await vi.waitFor(async () => {
        await settle();
        expect(settingsBlock().textContent).toContain(es.clinic.notGiven);
      });
      expect(rooms().textContent).toContain(es.clinic.locations.list.empty);
    });
  });

  it('keeps the other blocks usable when one fails to load', async () => {
    fixture = TestBed.createComponent(ClinicHome);
    fixture.detectChanges();
    (await expectRequest('GET', CLINIC)).flush(null, { status: 500, statusText: 'Error' });
    (await expectRequest('GET', SETTINGS)).flush(OPTIONS);
    (await expectRequest('GET', HOURS)).flush(WEEK);
    (await expectRequest('GET', LOCATIONS)).flush(page([ROOM_1]));
    await vi.waitFor(async () => {
      await settle();
      expect(data().querySelector('[role="alert"]')?.textContent).toContain(
        es.clinic.data.loadError,
      );
    });
    expect(field(settingsBlock(), 'defaultAppointmentMinutes').value).toBe('30');

    click(data(), es.clinic.retry);
    (await expectRequest('GET', CLINIC)).flush(DETAILS);
    await vi.waitFor(async () => {
      await settle();
      expect(field(data(), 'name')?.value).toBe('Clínica Prueba Norte');
    });
  });
});
