import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Session } from '../../core/auth/session';

import { es, translocoTesting } from '../../../testing/i18n';
import { ApiConfiguration } from '../../api/api-configuration';
import { ClinicUserDetails } from '../../api/models/clinic-user-details';
import { ClinicUsersHome } from './clinic-users-home';

const API = 'https://api.dentasoft.test';
const USERS = `${API}/api/v1/clinic/users`;

/** The signed-in clinic administrator. */
const ME: ClinicUserDetails = {
  id: 'm-1',
  userId: 'kc-admin',
  role: 'ADMIN_CLINICA',
  active: true,
  invitationPending: false,
  version: 0,
  profile: { email: 'admin@clinica-norte.test', firstName: 'Ana', lastName: 'Pérez' },
};
const LUIS: ClinicUserDetails = {
  id: 'm-2',
  userId: 'kc-luis',
  role: 'RECEPCION',
  active: true,
  invitationPending: false,
  version: 4,
  profile: { email: 'luis@clinica-norte.test', firstName: 'Luis', lastName: 'Martín' },
};
/** Invited, not accepted yet: no names. */
const PENDING: ClinicUserDetails = {
  id: 'm-3',
  userId: 'kc-pending',
  role: 'HIGIENISTA',
  active: true,
  invitationPending: true,
  version: 0,
  profile: { email: 'nueva@clinica-norte.test' },
};
const FORMER: ClinicUserDetails = {
  id: 'm-4',
  userId: 'kc-former',
  role: 'FACTURACION',
  active: false,
  invitationPending: false,
  version: 2,
  profile: { email: 'antes@clinica-norte.test', firstName: 'Eva', lastName: 'Ruiz' },
};

function page(content: ClinicUserDetails[], number = 0, totalElements = content.length) {
  return {
    content,
    page: { number, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
  };
}

function problem(status: number, detail: string, errors?: { field: string; message: string }[]) {
  return [
    { title: 'No se puede hacer', detail, errors },
    { status, statusText: 'Error' },
  ] as const;
}

/** The same, as the endpoints with no body receive it: the client reads their answer as text. */
function textProblem(
  status: number,
  detail: string,
  errors?: { field: string; message: string }[],
) {
  const [body, opts] = problem(status, detail, errors);
  return [JSON.stringify(body), opts] as const;
}

describe('ClinicUsersHome', () => {
  let fixture: ComponentFixture<ClinicUsersHome>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ClinicUsersHome, translocoTesting()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfiguration, useValue: { rootUrl: API } },
        { provide: Session, useValue: { userId: signal(ME.userId) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicUsersHome);
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

  async function expectRequest(method: string, url: string): Promise<TestRequest> {
    let found: TestRequest[] = [];
    await vi.waitFor(async () => {
      await fixture.whenStable();
      found = http.match((req) => req.method === method && req.url === url);
      expect(found.length).toBe(1);
    });
    return found[0];
  }

  async function show(content: ClinicUserDetails[], total = content.length): Promise<void> {
    fixture.detectChanges();
    (await expectRequest('GET', USERS)).flush(page(content, 0, total));
    await vi.waitFor(async () => {
      await settle();
      expect(root().textContent).not.toContain(es.clinicUsers.list.loading);
    });
  }

  function row(id: string): HTMLElement {
    return root().querySelector(`[data-user="${id}"]`) as HTMLElement;
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
    return root().querySelector('.clinic-users__outcome') as HTMLElement;
  }

  /** Chooses a role in the select of a row (a Material overlay outside the component). */
  async function chooseRole(id: string, role: string): Promise<void> {
    (row(id).querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
    await settle();
    let option: HTMLElement | undefined;
    await vi.waitFor(() => {
      option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
        (candidate) => candidate.textContent?.trim() === role,
      );
      expect(option).toBeTruthy();
    });
    option?.click();
    await settle();
  }

  describe('list', () => {
    it('asks for the first page in the backend order and shows each person with their access', async () => {
      fixture.detectChanges();
      const request = await expectRequest('GET', USERS);

      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('20');
      expect(request.request.params.has('sort')).toBe(false);
      request.flush(page([ME, LUIS, PENDING, FORMER]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('m-2')).not.toBeNull();
      });

      const region = root().querySelector('[role="region"]') as HTMLElement;
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(document.getElementById(region.getAttribute('aria-labelledby') ?? '')?.tagName).toBe(
        'CAPTION',
      );
      expect(row('m-2').querySelector('th')?.textContent?.trim()).toBe('Luis Martín');
      expect(row('m-2').textContent).toContain('luis@clinica-norte.test');
      expect(row('m-2').textContent).toContain(es.roles.RECEPCION);
      expect(row('m-2').textContent).toContain(es.clinicUsers.status.ACTIVE);
      // Until the person accepts, the backend has no name: the email stands for it.
      expect(row('m-3').querySelector('th')?.textContent?.trim()).toBe('nueva@clinica-norte.test');
      expect(row('m-3').textContent).toContain(es.clinicUsers.status.PENDING);
      expect(row('m-4').textContent).toContain(es.clinicUsers.status.INACTIVE);
    });

    it('goes to the next page', async () => {
      await show([ME], 25);

      (root().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
      const request = await expectRequest('GET', USERS);

      expect(request.request.params.get('page')).toBe('1');
      expect(request.request.params.has('sort')).toBe(false);
      request.flush(page([LUIS], 1, 25));
      await vi.waitFor(async () => {
        await settle();
        expect(row('m-2')).not.toBeNull();
      });
      expect(row('m-1')).toBeNull();
      expect(root().textContent).toContain('21–25 de 25');
    });

    it('says when the list could not be read and tries again', async () => {
      fixture.detectChanges();
      (await expectRequest('GET', USERS)).flush(null, { status: 500, statusText: 'Error' });
      await vi.waitFor(async () => {
        await settle();
        expect(root().textContent).toContain(es.clinicUsers.list.loadError);
      });

      click(root(), es.clinicUsers.list.retry);
      (await expectRequest('GET', USERS)).flush(page([ME]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('m-1')).not.toBeNull();
      });
      expect(root().textContent).not.toContain(es.clinicUsers.list.loadError);
    });

    it('says when there is nobody to show', async () => {
      await show([]);

      expect(root().textContent).toContain(es.clinicUsers.list.empty);
    });
  });

  describe('invitation', () => {
    function form(): HTMLElement {
      return root().querySelector('app-invite-user-form') as HTMLElement;
    }

    function input(name: string): HTMLInputElement {
      return form().querySelector(`[formcontrolname="${name}"]`) as HTMLInputElement;
    }

    async function type(name: string, value: string): Promise<void> {
      input(name).value = value;
      input(name).dispatchEvent(new Event('input'));
      await settle();
    }

    function radio(label: string): HTMLInputElement {
      const option = [...form().querySelectorAll('mat-radio-button')].find((candidate) =>
        candidate.textContent?.includes(label),
      );
      return option?.querySelector('input') as HTMLInputElement;
    }

    async function chooseInvitedRole(label: string): Promise<void> {
      radio(label).click();
      await settle();
    }

    async function send(): Promise<void> {
      (form().querySelector('.form-actions__save') as HTMLButtonElement).click();
      await settle();
    }

    async function fill(): Promise<void> {
      await type('email', 'odontologa@clinica-norte.test');
      await type('firstName', 'Marta');
      await type('lastName', 'López Sanz');
      await chooseInvitedRole(es.roles.ODONTOLOGO);
    }

    it('offers the five roles as radio buttons and labels its button', async () => {
      await show([ME]);

      expect(form().querySelectorAll('mat-radio-button')).toHaveLength(5);
      expect(form().querySelector('legend')?.textContent?.trim()).toBe(es.clinicUsers.invite.role);
      expect(form().querySelector('.form-actions__save')?.textContent?.trim()).toBe(
        es.clinicUsers.invite.send,
      );
      expect(input('email').getAttribute('autocomplete')).toBe('off');
      expect(input('email').getAttribute('autocapitalize')).toBe('none');
    });

    it('invites an odontologist, reloads the list and announces it (S3, clinic part)', async () => {
      await show([ME]);
      await fill();

      await send();
      const request = await expectRequest('POST', `${USERS}/invitations`);

      expect(request.request.body).toEqual({
        email: 'odontologa@clinica-norte.test',
        firstName: 'Marta',
        lastName: 'López Sanz',
        role: 'ODONTOLOGO',
      });
      request.flush('', { status: 202, statusText: 'Accepted' });
      const marta: ClinicUserDetails = {
        ...PENDING,
        id: 'm-5',
        role: 'ODONTOLOGO',
        profile: { email: 'odontologa@clinica-norte.test' },
      };
      (await expectRequest('GET', USERS)).flush(page([ME, marta]));
      await vi.waitFor(async () => {
        await settle();
        expect(row('m-5')).not.toBeNull();
      });

      expect(row('m-5').textContent).toContain(es.roles.ODONTOLOGO);
      expect(row('m-5').textContent).toContain(es.clinicUsers.status.PENDING);
      expect(form().querySelector('[role="status"]')?.textContent).toContain(
        'Invitación enviada a odontologa@clinica-norte.test.',
      );
      expect(input('email').value).toBe('');
      expect(radio(es.roles.ODONTOLOGO).checked).toBe(false);
    });

    it('refuses an invalid email without asking the server', async () => {
      await show([ME]);
      await fill();
      await type('email', 'no-es-un-email');

      await send();

      expect(form().textContent).toContain(es.forms.errors.email);
      expect(form().textContent).toContain(es.forms.invalid);
      expect(document.activeElement).toBe(input('email'));
    });

    it('asks for a role and takes the focus to it', async () => {
      await show([ME]);
      await type('email', 'odontologa@clinica-norte.test');
      await type('firstName', 'Marta');
      await type('lastName', 'López Sanz');

      await send();

      expect(form().querySelector('.invite-user__error')?.textContent).toContain(
        es.forms.errors.required,
      );
      expect(document.activeElement).toBe(radio(es.roles.ADMIN_CLINICA));

      await chooseInvitedRole(es.roles.HIGIENISTA);
      expect(form().querySelector('.invite-user__error')?.textContent?.trim()).toBe('');
    });

    it('puts the field errors of the server on their fields', async () => {
      await show([ME]);
      await fill();

      await send();
      (await expectRequest('POST', `${USERS}/invitations`)).flush(
        ...textProblem(400, 'Datos no válidos', [
          { field: 'email', message: 'El email no es válido.' },
        ]),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('mat-error')?.textContent).toContain('El email no es válido.');
      });
      expect(document.activeElement).toBe(input('email'));
    });

    it('shows the explanation when there were too many invitations today', async () => {
      await show([ME]);
      await fill();

      await send();
      (await expectRequest('POST', `${USERS}/invitations`)).flush(
        ...textProblem(429, 'La clínica ya ha enviado 20 invitaciones hoy. Prueba mañana.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(form().querySelector('[role="alert"]')?.textContent).toContain(
          'La clínica ya ha enviado 20 invitaciones hoy. Prueba mañana.',
        );
      });
      expect(input('email').value).toBe('odontologa@clinica-norte.test');
    });
  });

  describe('resend', () => {
    it('is offered only for pending invitations', async () => {
      await show([ME, LUIS, PENDING, FORMER]);

      expect(button(row('m-3'), es.clinicUsers.actions.resend)).toBeTruthy();
      expect(button(row('m-1'), es.clinicUsers.actions.resend)).toBeUndefined();
      expect(button(row('m-2'), es.clinicUsers.actions.resend)).toBeUndefined();
      expect(button(row('m-4'), es.clinicUsers.actions.resend)).toBeUndefined();
    });

    it('sends the invitation again and announces it', async () => {
      await show([ME, PENDING]);

      click(row('m-3'), es.clinicUsers.actions.resend);
      (await expectRequest('POST', `${USERS}/m-3/invitation`)).flush('', {
        status: 202,
        statusText: 'Accepted',
      });

      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
          'Invitación reenviada a nueva@clinica-norte.test.',
        );
      });
    });

    it('shows why a resend was refused', async () => {
      await show([ME, PENDING]);

      click(row('m-3'), es.clinicUsers.actions.resend);
      (await expectRequest('POST', `${USERS}/m-3/invitation`)).flush(
        ...textProblem(429, 'Demasiadas invitaciones hoy. Vuelve a intentarlo en 3 horas.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="alert"]')?.textContent).toContain(
          'Demasiadas invitaciones hoy. Vuelve a intentarlo en 3 horas.',
        );
      });
    });
  });

  describe('role change', () => {
    it('changes nothing until the user applies it, then sends the role with the version', async () => {
      await show([ME, LUIS]);
      const apply = () => button(row('m-2'), es.clinicUsers.actions.applyRole) as HTMLButtonElement;
      expect(apply().disabled).toBe(true);

      await chooseRole('m-2', es.roles.ODONTOLOGO);
      expect(http.match((req) => req.url.endsWith('/role'))).toEqual([]);
      expect(apply().disabled).toBe(false);

      apply().click();
      const request = await expectRequest('PUT', `${USERS}/m-2/role`);

      expect(request.request.body).toEqual({ role: 'ODONTOLOGO', version: 4 });
      request.flush({ ...LUIS, role: 'ODONTOLOGO', version: 5 });
      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
          'Rol de Luis Martín cambiado a «Odontología».',
        );
      });
      expect(row('m-2').textContent).toContain(es.roles.ODONTOLOGO);
      expect(apply().disabled).toBe(true);

      // The next change goes with the new version.
      await chooseRole('m-2', es.roles.HIGIENISTA);
      apply().click();
      const next = await expectRequest('PUT', `${USERS}/m-2/role`);
      expect(next.request.body).toEqual({ role: 'HIGIENISTA', version: 5 });
      next.flush({ ...LUIS, role: 'HIGIENISTA', version: 6 });
      await settle();
    });

    it('shows why the change was refused', async () => {
      await show([ME, LUIS]);

      await chooseRole('m-2', es.roles.ADMIN_CLINICA);
      click(row('m-2'), es.clinicUsers.actions.applyRole);
      (await expectRequest('PUT', `${USERS}/m-2/role`)).flush(
        ...problem(409, 'Otra persona ha cambiado este usuario. Vuelve a cargar la lista.'),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="alert"]')?.textContent).toContain(
          'Otra persona ha cambiado este usuario. Vuelve a cargar la lista.',
        );
      });
      expect(row('m-2').textContent).toContain(es.roles.RECEPCION);
    });
  });

  describe('deactivation', () => {
    it('asks first and sends nothing if the user cancels', async () => {
      await show([ME, LUIS]);

      click(row('m-2'), es.clinicUsers.actions.deactivate);
      const cancel = await dialogButton('.confirm-dialog__cancel');
      expect(document.querySelector('mat-dialog-content')?.textContent).toContain('Luis Martín');
      cancel.click();
      await settle();

      expect(http.match((req) => req.url.endsWith('/deactivation'))).toEqual([]);
    });

    it('deactivates after confirming, with the version, and shows the new access', async () => {
      await show([ME, LUIS]);

      click(row('m-2'), es.clinicUsers.actions.deactivate);
      (await dialogButton('.confirm-dialog__confirm')).click();
      const request = await expectRequest('POST', `${USERS}/m-2/deactivation`);

      expect(request.request.body).toEqual({ version: 4 });
      request.flush({ ...LUIS, active: false, version: 5 });
      await vi.waitFor(async () => {
        await settle();
        expect(row('m-2').textContent).toContain(es.clinicUsers.status.INACTIVE);
      });
      expect(outcome().querySelector('[role="status"]')?.textContent).toContain(
        'Luis Martín ya no tiene acceso a la clínica.',
      );
      expect(button(row('m-2'), es.clinicUsers.actions.deactivate)).toBeUndefined();
      expect(row('m-2').querySelector('mat-select')).toBeNull();
    });

    it('shows why the deactivation was refused', async () => {
      await show([ME, LUIS]);

      click(row('m-2'), es.clinicUsers.actions.deactivate);
      (await dialogButton('.confirm-dialog__confirm')).click();
      (await expectRequest('POST', `${USERS}/m-2/deactivation`)).flush(
        ...problem(
          409,
          'La clínica tiene que tener al menos una persona con el rol de administración.',
        ),
      );

      await vi.waitFor(async () => {
        await settle();
        expect(outcome().querySelector('[role="alert"]')?.textContent).toContain(
          'La clínica tiene que tener al menos una persona con el rol de administración.',
        );
      });
      expect(row('m-2').textContent).toContain(es.clinicUsers.status.ACTIVE);
    });
  });

  it('offers no role change or deactivation on the user’s own row', async () => {
    await show([ME, LUIS]);

    expect(row('m-1').querySelector('th')?.textContent?.trim()).toBe('Ana Pérez (tú)');
    expect(row('m-1').querySelector('mat-select')).toBeNull();
    expect(button(row('m-1'), es.clinicUsers.actions.applyRole)).toBeUndefined();
    expect(button(row('m-1'), es.clinicUsers.actions.deactivate)).toBeUndefined();
    expect(row('m-1').textContent).toContain(es.clinicUsers.actions.self);
    expect(row('m-2').querySelector('mat-select')).not.toBeNull();
  });
});
