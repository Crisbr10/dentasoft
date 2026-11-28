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
import { AdminInvitationStatus } from '../../api/models/admin-invitation-status';
import { PlatformClinicDetails } from '../../api/models/platform-clinic-details';
import { PlatformHome } from './platform-home';

const API = 'https://api.dentasoft.test';
const CLINICS = `${API}/api/v1/platform/clinics`;

const NORTE: PlatformClinicDetails = {
  id: 'c-1',
  name: 'Clínica Norte',
  legalName: 'Clínica Norte SL',
  taxId: 'B12345674',
  status: 'ACTIVE',
  version: 3,
};
const SUR: PlatformClinicDetails = {
  id: 'c-2',
  name: 'Clínica Sur',
  legalName: 'Clínica Sur SL',
  taxId: 'A58818501',
  status: 'SUSPENDED',
  version: 1,
};

const NONE: AdminInvitationStatus = { status: 'NONE' };
const PENDING: AdminInvitationStatus = { status: 'PENDING', invitedAt: '2026-11-20T10:00:00Z' };
const ACCEPTED: AdminInvitationStatus = {
  status: 'ACCEPTED',
  invitedAt: '2026-11-20T10:00:00Z',
  acceptedAt: '2026-11-21T09:30:00Z',
};

function page(content: PlatformClinicDetails[], number = 0, totalElements = content.length) {
  return {
    content,
    page: { number, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
  };
}

function problem(status: number, detail: string, errors?: { field: string; message: string }[]) {
  return [
    { title: 'Conflicto', detail, errors },
    { status, statusText: 'Error' },
  ] as const;
}

/** The same, as the endpoints with no body receive it: the client reads their answer as text. */
function textProblem(status: number, detail: string) {
  const [body, opts] = problem(status, detail);
  return [JSON.stringify(body), opts] as const;
}

describe('PlatformHome', () => {
  let fixture: ComponentFixture<PlatformHome>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PlatformHome, translocoTesting()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PlatformHome);
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

  /** The next request matching method and URL, once the component made it. */
  async function expectRequest(method: string, url: string): Promise<TestRequest> {
    let found: TestRequest[] = [];
    await vi.waitFor(async () => {
      await fixture.whenStable();
      found = http.match((req) => req.method === method && req.url === url);
      expect(found.length).toBe(1);
    });
    return found[0];
  }

  async function show(content: PlatformClinicDetails[], total = content.length): Promise<void> {
    fixture.detectChanges();
    (await expectRequest('GET', CLINICS)).flush(page(content, 0, total));
    await vi.waitFor(async () => {
      await settle();
      expect(root().textContent).not.toContain(es.platform.list.loading);
    });
  }

  function row(id: string): HTMLElement {
    return root().querySelector(`[data-clinic="${id}"]`) as HTMLElement;
  }

  function section(selector: string): HTMLElement {
    return root().querySelector(selector) as HTMLElement;
  }

  function input(scope: HTMLElement, name: string): HTMLInputElement {
    return scope.querySelector(`[formcontrolname="${name}"]`) as HTMLInputElement;
  }

  async function type(scope: HTMLElement, name: string, value: string): Promise<void> {
    const field = input(scope, name);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    await settle();
  }

  async function save(scope: HTMLElement): Promise<void> {
    (scope.querySelector('.form-actions__save') as HTMLButtonElement).click();
    await settle();
  }

  function button(scope: HTMLElement, text: string): HTMLButtonElement {
    const found = [...scope.querySelectorAll('button')].find(
      (candidate) => candidate.textContent?.trim() === text,
    );
    if (!found) {
      throw new Error(`No button ${text}`);
    }
    return found;
  }

  async function dialogButton(selector: string): Promise<HTMLButtonElement> {
    // The last one: a dialog closed by an earlier step may still be leaving the page. Its text
    // shows that the dialog was rendered, with the result of each button bound.
    let found: HTMLButtonElement | undefined;
    await vi.waitFor(() => {
      found = [...document.querySelectorAll<HTMLButtonElement>(selector)].at(-1);
      expect(found?.textContent?.trim()).toBeTruthy();
    });
    return found as HTMLButtonElement;
  }

  /** Opens the panel of a clinic and answers the status of its invitation. */
  async function open(clinic: PlatformClinicDetails, invitation: AdminInvitationStatus) {
    button(row(clinic.id ?? ''), es.platform.list.manage).click();
    await settle();
    (await expectRequest('GET', `${CLINICS}/${clinic.id}/admin-invitation`)).flush(invitation);
    await vi.waitFor(async () => {
      await settle();
      expect(section('app-first-admin').textContent).not.toContain(es.platform.admin.loading);
    });
    return section('app-clinic-panel');
  }

  describe('list', () => {
    it('asks for the first page by name and shows each clinic with its status as text', async () => {
      fixture.detectChanges();
      const request = await expectRequest('GET', CLINICS);

      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('20');
      expect(request.request.params.get('sort')).toBe('name');
      expect(request.request.headers.has('X-Clinic-Id')).toBe(false);
      request.flush(page([NORTE, SUR]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('c-1')).not.toBeNull();
      });

      const region = root().querySelector('[role="region"]') as HTMLElement;
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(document.getElementById(region.getAttribute('aria-labelledby') ?? '')?.tagName).toBe(
        'CAPTION',
      );
      expect(row('c-1').querySelector('th')?.textContent?.trim()).toBe('Clínica Norte');
      expect(row('c-1').textContent).toContain('B12345674');
      expect(row('c-1').textContent).toContain(es.platform.status.ACTIVE);
      expect(row('c-2').textContent).toContain(es.platform.status.SUSPENDED);
    });

    it('goes to the next page', async () => {
      await show([NORTE], 25);

      (root().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
      const request = await expectRequest('GET', CLINICS);

      expect(request.request.params.get('page')).toBe('1');
      request.flush(page([SUR], 1, 25));
      await vi.waitFor(async () => {
        await settle();
        expect(row('c-2')).not.toBeNull();
      });
      expect(row('c-1')).toBeNull();
      expect(root().textContent).toContain('21–25 de 25');
    });

    it('says when there are no clinics', async () => {
      await show([]);

      expect(root().textContent).toContain(es.platform.list.empty);
    });

    it('says when the list could not be read and tries again', async () => {
      fixture.detectChanges();
      (await expectRequest('GET', CLINICS)).flush(null, { status: 500, statusText: 'Error' });
      await vi.waitFor(async () => {
        await settle();
        expect(root().textContent).toContain(es.platform.list.loadError);
      });

      button(root(), es.platform.list.retry).click();
      (await expectRequest('GET', CLINICS)).flush(page([NORTE]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('c-1')).not.toBeNull();
      });
      expect(root().textContent).not.toContain(es.platform.list.loadError);
    });
  });

  describe('new clinic', () => {
    function form(): HTMLElement {
      return section('app-new-clinic-form');
    }

    it('creates the clinic with the tax id normalized, reloads the list and opens it', async () => {
      await show([NORTE]);
      await type(form(), 'name', 'Clínica Este');
      await type(form(), 'legalName', 'Clínica Este SL');
      await type(form(), 'taxId', 'a-5881 8501');

      await save(form());
      const request = await expectRequest('POST', CLINICS);

      expect(request.request.body).toEqual({
        name: 'Clínica Este',
        legalName: 'Clínica Este SL',
        taxId: 'A58818501',
      });
      const este = { ...SUR, id: 'c-3', name: 'Clínica Este', status: 'ACTIVE' as const };
      request.flush(este);
      (await expectRequest('GET', CLINICS)).flush(page([este, NORTE]));
      (await expectRequest('GET', `${CLINICS}/c-3/admin-invitation`)).flush(NONE);
      await settle();

      expect(row('c-3').getAttribute('aria-current')).toBe('true');
      expect(section('app-clinic-panel h3').textContent).toContain('Clínica Este');
      expect(input(form(), 'name').value).toBe('');
    });

    it('refuses a tax id with a wrong check digit without asking the server', async () => {
      await show([NORTE]);
      await type(form(), 'name', 'Clínica Este');
      await type(form(), 'legalName', 'Clínica Este SL');
      await type(form(), 'taxId', 'B12345670');

      await save(form());

      expect(form().textContent).toContain(es.platform.errors.taxId);
      expect(form().textContent).toContain(es.forms.invalid);
      expect(document.activeElement).toBe(input(form(), 'taxId'));
    });

    it('marks the required fields and focuses the first one', async () => {
      await show([NORTE]);
      await type(form(), 'taxId', 'B12345674');

      await save(form());

      expect(form().textContent).toContain(es.platform.errors.required);
      expect(document.activeElement).toBe(input(form(), 'name'));
    });

    it('puts the field errors of the server on their fields', async () => {
      await show([NORTE]);
      await type(form(), 'name', 'Clínica Este');
      await type(form(), 'legalName', 'Clínica Este SL');
      await type(form(), 'taxId', 'B12345674');

      await save(form());
      (await expectRequest('POST', CLINICS)).flush(
        ...problem(400, 'Datos no válidos', [
          { field: 'taxId', message: 'Ya hay una clínica con ese CIF.' },
        ]),
      );
      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('mat-error')?.textContent).toContain(
          'Ya hay una clínica con ese CIF.',
        );
      });
      expect(document.activeElement).toBe(input(form(), 'taxId'));
    });
  });

  describe('clinic panel', () => {
    it('asks before suspending and sends nothing if the user cancels', async () => {
      await show([NORTE]);
      const panel = await open(NORTE, NONE);

      button(panel, es.platform.panel.suspend).click();
      (await dialogButton('.confirm-dialog__cancel')).click();
      await settle();

      expect(http.match((req) => req.url.endsWith('/suspension'))).toEqual([]);
    });

    it('suspends after confirming and shows the new status in the panel and the row', async () => {
      await show([NORTE]);
      const panel = await open(NORTE, NONE);

      button(panel, es.platform.panel.suspend).click();
      (await dialogButton('.confirm-dialog__confirm')).click();
      (await expectRequest('POST', `${CLINICS}/c-1/suspension`)).flush({
        ...NORTE,
        status: 'SUSPENDED',
        version: 4,
      });
      await vi.waitFor(async () => {
        await settle();
        expect(panel.textContent).toContain(es.platform.panel.suspended);
      });

      expect(row('c-1').textContent).toContain(es.platform.status.SUSPENDED);
      expect(button(panel, es.platform.panel.reactivate)).toBeTruthy();
    });

    it('reactivates without asking', async () => {
      await show([SUR]);
      const panel = await open(SUR, ACCEPTED);

      button(panel, es.platform.panel.reactivate).click();
      (await expectRequest('POST', `${CLINICS}/c-2/reactivation`)).flush({
        ...SUR,
        status: 'ACTIVE',
        version: 2,
      });
      await vi.waitFor(async () => {
        await settle();
        expect(row('c-2').textContent).toContain(es.platform.status.ACTIVE);
      });
      expect(document.querySelector('.confirm-dialog__confirm')).toBeNull();
    });

    it('shows why a suspension failed', async () => {
      await show([NORTE]);
      const panel = await open(NORTE, NONE);

      button(panel, es.platform.panel.suspend).click();
      (await dialogButton('.confirm-dialog__confirm')).click();
      (await expectRequest('POST', `${CLINICS}/c-1/suspension`)).flush(
        ...problem(409, 'La clínica ya está suspendida.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(panel.querySelector('[role="alert"]')?.textContent).toContain(
          'La clínica ya está suspendida.',
        );
      });
    });
  });

  describe('fiscal data', () => {
    function form(): HTMLElement {
      return section('app-fiscal-data-form');
    }

    it('starts with the clinic data and sends the correction with the version', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);
      expect(input(form(), 'legalName').value).toBe('Clínica Norte SL');
      expect(input(form(), 'taxId').value).toBe('B12345674');

      await type(form(), 'legalName', 'Norte Dental SL');
      await type(form(), 'taxId', '12345678-z');
      await save(form());
      const request = await expectRequest('PUT', `${CLINICS}/c-1/fiscal-data`);

      expect(request.request.body).toEqual({
        legalName: 'Norte Dental SL',
        taxId: '12345678Z',
        version: 3,
      });
      request.flush({ ...NORTE, legalName: 'Norte Dental SL', taxId: '12345678Z', version: 4 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('c-1').textContent).toContain('12345678Z');
      });
      expect(form().parentElement?.textContent).toContain(es.platform.fiscal.saved);
      expect(input(form(), 'taxId').value).toBe('12345678Z');

      // The next correction goes with the new version.
      await type(form(), 'legalName', 'Norte Dental Grupo SL');
      await save(form());
      const next = await expectRequest('PUT', `${CLINICS}/c-1/fiscal-data`);
      expect(next.request.body.version).toBe(4);
      next.flush({ ...NORTE, legalName: 'Norte Dental Grupo SL', version: 5 });
      await settle();
    });

    it('refuses an invalid tax id without asking the server', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);

      await type(form(), 'taxId', 'X1234567A');
      await save(form());

      expect(form().textContent).toContain(es.platform.errors.taxId);
      expect(document.activeElement).toBe(input(form(), 'taxId'));
    });

    it('shows the explanation of a version conflict', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);

      await type(form(), 'legalName', 'Norte Dental SL');
      await save(form());
      (await expectRequest('PUT', `${CLINICS}/c-1/fiscal-data`)).flush(
        ...problem(409, 'Otra persona ha cambiado la clínica. Vuelve a cargarla.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'Otra persona ha cambiado la clínica. Vuelve a cargarla.',
        );
      });
    });
  });

  describe('first administrator', () => {
    function form(): HTMLElement {
      return section('app-first-admin');
    }

    async function fillInvitation(): Promise<void> {
      await type(form(), 'email', 'admin@clinica-norte.test');
      await type(form(), 'firstName', 'Ana');
      await type(form(), 'lastName', 'Pérez Gómez');
    }

    it('invites someone when nobody was invited, and shows the pending invitation', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);
      expect(form().textContent).toContain(es.platform.admin.none);

      await fillInvitation();
      await save(form());
      const request = await expectRequest('POST', `${CLINICS}/c-1/admin-invitation`);

      expect(request.request.body).toEqual({
        email: 'admin@clinica-norte.test',
        firstName: 'Ana',
        lastName: 'Pérez Gómez',
      });
      expect(request.request.headers.has('X-Clinic-Id')).toBe(false);
      request.flush(null, { status: 202, statusText: 'Accepted' });
      (await expectRequest('GET', `${CLINICS}/c-1/admin-invitation`)).flush(PENDING);
      await vi.waitFor(async () => {
        await settle();
        expect(form().textContent).toContain('Pendiente de aceptar');
      });
      expect(form().querySelector('[role="status"]')?.textContent).toContain(
        es.platform.admin.invited,
      );
      expect(form().textContent).toContain('2026');
    });

    it('refuses an invalid email without asking the server', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);

      await type(form(), 'email', 'no-es-un-email');
      await type(form(), 'firstName', 'Ana');
      await type(form(), 'lastName', 'Pérez');
      await save(form());

      expect(form().textContent).toContain(es.platform.errors.email);
      expect(document.activeElement).toBe(input(form(), 'email'));
    });

    it('shows the explanation when the invitation is refused', async () => {
      await show([NORTE]);
      await open(NORTE, NONE);

      await fillInvitation();
      await save(form());
      (await expectRequest('POST', `${CLINICS}/c-1/admin-invitation`)).flush(
        ...textProblem(409, 'La clínica ya tiene una invitación pendiente.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'La clínica ya tiene una invitación pendiente.',
        );
      });
    });

    it('sends a pending invitation again', async () => {
      await show([NORTE]);
      await open(NORTE, PENDING);

      button(form(), es.platform.admin.resend).click();
      (await expectRequest('POST', `${CLINICS}/c-1/admin-invitation/resend`)).flush(null, {
        status: 202,
        statusText: 'Accepted',
      });
      (await expectRequest('GET', `${CLINICS}/c-1/admin-invitation`)).flush(PENDING);

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="status"]')?.textContent).toContain(
          es.platform.admin.resent,
        );
      });
    });

    it('shows why a resend failed', async () => {
      await show([NORTE]);
      await open(NORTE, PENDING);

      button(form(), es.platform.admin.resend).click();
      (await expectRequest('POST', `${CLINICS}/c-1/admin-invitation/resend`)).flush(
        ...textProblem(429, 'Espera unos minutos antes de reenviarla.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'Espera unos minutos antes de reenviarla.',
        );
      });
    });

    it('asks before cancelling, and cancels only after confirming', async () => {
      await show([NORTE]);
      await open(NORTE, PENDING);

      button(form(), es.platform.admin.cancel).click();
      (await dialogButton('.confirm-dialog__cancel')).click();
      await settle();
      expect(http.match((req) => req.url.endsWith('/cancellation'))).toEqual([]);

      button(form(), es.platform.admin.cancel).click();
      (await dialogButton('.confirm-dialog__confirm')).click();
      (await expectRequest('POST', `${CLINICS}/c-1/admin-invitation/cancellation`)).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      (await expectRequest('GET', `${CLINICS}/c-1/admin-invitation`)).flush(NONE);

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="status"]')?.textContent).toContain(
          es.platform.admin.cancelled,
        );
        expect(input(form(), 'email')).not.toBeNull();
      });
    });

    it('shows when the invitation was accepted, with nothing more to do', async () => {
      await show([NORTE]);
      await open(NORTE, ACCEPTED);

      expect(form().textContent).toContain('aceptó la invitación');
      expect(form().querySelectorAll('button')).toHaveLength(0);
      expect(input(form(), 'email')).toBeNull();
    });
  });

  it('creates a clinic and invites its first administrator (S3, platform part)', async () => {
    await show([]);
    const newClinic = section('app-new-clinic-form');
    await type(newClinic, 'name', 'Clínica Este');
    await type(newClinic, 'legalName', 'Clínica Este SL');
    await type(newClinic, 'taxId', 'B12345674');
    await save(newClinic);
    const este = { ...NORTE, id: 'c-3', name: 'Clínica Este', version: 0 };
    (await expectRequest('POST', CLINICS)).flush(este);
    (await expectRequest('GET', CLINICS)).flush(page([este]));
    (await expectRequest('GET', `${CLINICS}/c-3/admin-invitation`)).flush(NONE);
    await settle();

    const admin = section('app-first-admin');
    await type(admin, 'email', 'admin@clinica-este.test');
    await type(admin, 'firstName', 'Luis');
    await type(admin, 'lastName', 'Martín');
    await save(admin);
    const invitation = await expectRequest('POST', `${CLINICS}/c-3/admin-invitation`);
    expect(invitation.request.body).toEqual({
      email: 'admin@clinica-este.test',
      firstName: 'Luis',
      lastName: 'Martín',
    });
    invitation.flush(null, { status: 202, statusText: 'Accepted' });
    (await expectRequest('GET', `${CLINICS}/c-3/admin-invitation`)).flush(PENDING);

    await vi.waitFor(async () => {
      await settle();
      expect(admin.textContent).toContain('Pendiente de aceptar');
    });
  });
});
