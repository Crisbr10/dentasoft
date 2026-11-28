import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  Signal,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, merge } from 'rxjs';

import { Api } from '../../api/api';
import { list3 } from '../../api/fn/clinic-users-controller/list-3';
import { create1 } from '../../api/fn/practitioner-roles-controller/create-1';
import { update } from '../../api/fn/practitioner-roles-controller/update';
import { ClinicUserDetails } from '../../api/models/clinic-user-details';
import { PractitionerDetails } from '../../api/models/practitioner-details';
import { PractitionerRoleRequest } from '../../api/models/practitioner-role-request';
import { ClinicRole } from '../../core/clinic/active-clinic';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { displayName } from '../clinic-users/clinic-user-names';
import {
  DISPLAY_NAME_MAX,
  PROVINCES,
  SPECIALTIES,
  Specialty,
  agendaColor,
  licenseError,
  licenseErrorState,
  licenseNumber,
  licenseRules,
  personName,
} from './practitioner-rules';

/** Users read per request (the backend accepts up to 50). */
const USERS_PAGE_SIZE = 50;

/** The colour a new practitioner starts with; the native picker always has one. */
const DEFAULT_COLOR = '#1E88E5';

/** Only members with these roles may have a practitioner record (`requireEligible`). */
const CLINICAL_ROLES: readonly ClinicRole[] = ['ODONTOLOGO', 'HIGIENISTA'];

/** A choice of the user select: a clinic member, or the linked user when it is no longer one. */
interface UserOption {
  readonly userId: string;
  readonly name: string | null;
  readonly role: ClinicRole | null;
}

/** What the form saved, so the list can show it. */
export interface SavedPractitioner {
  readonly practitioner: PractitionerDetails;
  readonly created: boolean;
}

/** Active, as ODONTOLOGO or HIGIENISTA; a pending invitation counts, as in the backend. */
function eligible(user: ClinicUserDetails): boolean {
  return user.active === true && user.role !== undefined && CLINICAL_ROLES.includes(user.role);
}

/**
 * Creates a practitioner record of the active clinic (POST /api/v1/clinic/practitioner-roles) or,
 * with `practitioner`, replaces one's data with the version the user saw (PUT …/{id}), as M02-T06
 * defines them. The fields are checked as the backend checks them; the backend checks them again,
 * and its conflicts (repeated licence, user already linked, area that does not suit the user's
 * role, version) come as its ProblemDetail. The linked user, optional, is a clinic member with the
 * role ODONTOLOGO or HIGIENISTA, pending invitations included, and cannot change once set. Only for
 * ADMIN_CLINICA: the list of users it reads is theirs alone.
 */
@Component({
  selector: 'app-practitioner-form',
  imports: [
    ControlError,
    FormActions,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="practitioner-form-title">
      @if (practitioner(); as editing) {
        {{ 'practitioners.form.editTitle' | transloco: { name: editing.displayName } }}
      } @else {
        {{ 'practitioners.form.newTitle' | transloco }}
      }
    </h3>
    <form
      class="practitioner-form"
      [attr.aria-labelledby]="'practitioner-form-title'"
      [formGroup]="form"
    >
      <mat-form-field>
        <mat-label>{{ 'practitioners.form.displayName' | transloco }}</mat-label>
        <input matInput formControlName="displayName" autocomplete="off" required />
        <mat-error>
          @if (nameInvalid()) {
            {{ 'forms.errors.personName' | transloco }}
          } @else {
            <app-control-error [control]="form.controls.displayName" />
          }
        </mat-error>
      </mat-form-field>

      <mat-form-field>
        <mat-label>{{ 'practitioners.form.area' | transloco }}</mat-label>
        <mat-select formControlName="specialty" required>
          @for (area of specialties; track area) {
            <mat-option [value]="area">{{ 'practitioners.areas.' + area | transloco }}</mat-option>
          }
        </mat-select>
        <mat-error><app-control-error [control]="form.controls.specialty" /></mat-error>
      </mat-form-field>

      <fieldset class="practitioner-form__license">
        <legend>{{ 'practitioners.form.license' | transloco }}</legend>
        <p class="practitioner-form__hint">{{ 'practitioners.form.licenseHint' | transloco }}</p>
        <mat-form-field>
          <mat-label>{{ 'practitioners.form.licenseProvince' | transloco }}</mat-label>
          <mat-select
            formControlName="licenseProvince"
            [errorStateMatcher]="licenseMatcher"
            [aria-describedby]="licenseDescribedBy()"
          >
            <mat-option [value]="null">{{ 'practitioners.form.noLicense' | transloco }}</mat-option>
            @for (code of provinces; track code) {
              <mat-option [value]="code">{{
                'practitioners.provinces.' + code | transloco
              }}</mat-option>
            }
          </mat-select>
          <mat-error><app-control-error [control]="form.controls.licenseProvince" /></mat-error>
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ 'practitioners.form.licenseNumber' | transloco }}</mat-label>
          <input
            matInput
            formControlName="licenseNumber"
            autocomplete="off"
            spellcheck="false"
            [attr.autocapitalize]="'characters'"
            [errorStateMatcher]="licenseMatcher"
            [aria-describedby]="licenseDescribedBy()"
          />
          <mat-error>
            @if (numberInvalid()) {
              {{ 'practitioners.form.errors.licenseNumber' | transloco }}
            } @else {
              <app-control-error [control]="form.controls.licenseNumber" />
            }
          </mat-error>
        </mat-form-field>
        <p id="practitioner-license-error" class="practitioner-form__license-error">
          @if (shownLicenseError(); as error) {
            {{ 'practitioners.form.errors.' + error | transloco }}
          }
        </p>
      </fieldset>

      <div class="practitioner-form__color">
        <label for="practitioner-color">{{ 'practitioners.form.color' | transloco }}</label>
        <input
          id="practitioner-color"
          type="color"
          formControlName="agendaColor"
          [attr.aria-describedby]="'practitioner-color-value'"
        />
        <span id="practitioner-color-value" class="practitioner-form__color-value">{{
          color()
        }}</span>
      </div>

      <mat-form-field>
        <mat-label>{{ 'practitioners.form.user' | transloco }}</mat-label>
        <mat-select formControlName="userId">
          <mat-option [value]="null">{{ 'practitioners.form.noUser' | transloco }}</mat-option>
          @for (user of userOptions(); track user.userId) {
            <mat-option [value]="user.userId">
              @if (user.name !== null && user.role !== null) {
                {{
                  'practitioners.form.userOption'
                    | transloco: { name: user.name, role: 'roles.' + user.role | transloco }
                }}
              } @else {
                {{ 'practitioners.form.linkedUser' | transloco }}
              }
            </mat-option>
          }
        </mat-select>
        <mat-hint>
          {{
            (userFixed() ? 'practitioners.form.userFixed' : 'practitioners.form.userHint')
              | transloco
          }}
        </mat-hint>
        <mat-error><app-control-error [control]="form.controls.userId" /></mat-error>
      </mat-form-field>
      @if (usersFailed()) {
        <p class="practitioner-form__hint">{{ 'practitioners.form.usersLoadError' | transloco }}</p>
      }

      <app-form-actions
        [form]="form"
        [saving]="saving()"
        [failure]="failure()"
        [saveKey]="practitioner() ? 'forms.save' : 'practitioners.form.create'"
        (save)="save()"
        (discard)="discard()"
      />
      @if (practitioner()) {
        <div class="practitioner-form__cancel">
          <button mat-button type="button" (click)="cancelled.emit()">
            {{ 'practitioners.form.cancelEdit' | transloco }}
          </button>
        </div>
      }
    </form>
  `,
  styles: `
    .practitioner-form {
      display: flex;
      flex-direction: column;
      max-inline-size: 32rem;
    }

    .practitioner-form__license {
      display: flex;
      flex-direction: column;
      margin: 0 0 8px;
      padding: 0;
      border: none;

      legend {
        font: var(--mat-sys-body-large);
        padding: 0;
      }
    }

    .practitioner-form__hint {
      margin-block: 0 8px;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }

    .practitioner-form__license-error {
      margin: 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-small);
    }

    .practitioner-form__color {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-block-end: 16px;

      input {
        inline-size: 3rem;
        block-size: 2.5rem;
        padding: 2px;
        border: 1px solid var(--mat-sys-outline);
        border-radius: 4px;
        background: var(--mat-sys-surface);
        // The swatch is the information: forced colours must not repaint it.
        forced-color-adjust: none;
      }
    }

    .practitioner-form__color-value {
      font-family: monospace;
    }

    .practitioner-form__cancel {
      display: flex;
      justify-content: flex-end;
      margin-block-start: 8px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PractitionerForm implements OnInit {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The record to edit; none for a new one. */
  readonly practitioner = input<PractitionerDetails | null>(null);
  readonly saved = output<SavedPractitioner>();
  /** The user left the edit without saving. */
  readonly cancelled = output<void>();

  protected readonly specialties = SPECIALTIES;
  protected readonly provinces = PROVINCES;
  protected readonly licenseMatcher = licenseErrorState;

  protected readonly form = new FormGroup(
    {
      displayName: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(DISPLAY_NAME_MAX), personName],
      }),
      specialty: new FormControl<Specialty | null>(null, { validators: [Validators.required] }),
      licenseProvince: new FormControl<string | null>(null),
      licenseNumber: new FormControl('', { nonNullable: true, validators: [licenseNumber] }),
      agendaColor: new FormControl(DEFAULT_COLOR, {
        nonNullable: true,
        validators: [Validators.required, agendaColor],
      }),
      userId: new FormControl<string | null>(null),
    },
    { validators: licenseRules },
  );
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);

  /** The clinic's users, read once; the select offers those who may be linked. */
  private readonly users = signal<readonly ClinicUserDetails[]>([]);
  protected readonly usersFailed = signal(false);

  protected readonly color = toSignal(
    this.form.controls.agendaColor.valueChanges.pipe(map((value) => value.toUpperCase())),
    { initialValue: DEFAULT_COLOR },
  );

  /** A name with no letters or with invisible characters (an error ControlError does not know). */
  protected readonly nameInvalid = this.watch(() => {
    const control = this.form.controls.displayName;
    return control.hasError('personName') && !control.hasError('server');
  });

  protected readonly numberInvalid = this.watch(() => {
    const control = this.form.controls.licenseNumber;
    return control.hasError('licenseNumber') && !control.hasError('server');
  });

  /** The licence as a whole, once one of its fields was touched (or after a submit). */
  protected readonly shownLicenseError = this.watch(() => {
    const { licenseProvince, licenseNumber } = this.form.controls;
    const touched = licenseProvince.touched || licenseNumber.touched;
    return touched ? licenseError(this.form) : null;
  });

  protected readonly licenseDescribedBy = computed(() =>
    this.shownLicenseError() ? 'practitioner-license-error' : '',
  );

  protected readonly userFixed = computed(() => !!this.practitioner()?.userId);

  protected readonly userOptions = computed<readonly UserOption[]>(() => {
    const users = this.users();
    const options: UserOption[] = users.flatMap((user) =>
      eligible(user) && user.userId && user.role
        ? [{ userId: user.userId, name: displayName(user), role: user.role }]
        : [],
    );
    const linked = this.practitioner()?.userId;
    if (linked && !options.some((option) => option.userId === linked)) {
      const user = users.find((candidate) => candidate.userId === linked);
      options.push({
        userId: linked,
        name: user ? displayName(user) : null,
        role: user?.role ?? null,
      });
    }
    return options;
  });

  constructor() {
    // A new record, or another one to edit: the form shows its data.
    effect(() => {
      const practitioner = this.practitioner();
      untracked(() => {
        this.fill(practitioner);
        this.failure.set(null);
        if (practitioner) {
          afterNextRender(
            () =>
              this.host.nativeElement
                .querySelector<HTMLElement>('[formcontrolname="displayName"]')
                ?.focus(),
            { injector: this.injector },
          );
        }
      });
    });
  }

  ngOnInit(): void {
    void this.loadUsers();
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      this.focusInvalid();
      return;
    }
    const editing = this.practitioner();
    const body = this.body();
    this.saving.set(true);
    this.failure.set(null);
    try {
      const practitioner = editing
        ? await this.api.invoke(update, {
            id: editing.id ?? '',
            body: { ...body, version: editing.version ?? 0 },
          })
        : await this.api.invoke(create1, { body });
      if (!editing) {
        this.fill(null);
      }
      this.saved.emit({ practitioner, created: !editing });
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      this.focusInvalid();
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.fill(this.practitioner());
    this.failure.set(null);
  }

  /** The request: the colour and licence number as the backend keeps them, empty fields left out. */
  private body(): PractitionerRoleRequest {
    const value = this.form.getRawValue();
    const number = value.licenseNumber.trim();
    return {
      ...(value.userId ? { userId: value.userId } : {}),
      displayName: value.displayName,
      specialty: value.specialty!,
      ...(value.licenseProvince ? { licenseProvince: value.licenseProvince } : {}),
      ...(number ? { licenseNumber: number.toUpperCase() } : {}),
      agendaColor: value.agendaColor.toUpperCase(),
    };
  }

  private fill(practitioner: PractitionerDetails | null): void {
    this.form.reset({
      displayName: practitioner?.displayName ?? '',
      specialty: practitioner?.specialty ?? null,
      licenseProvince: practitioner?.licenseProvince ?? null,
      licenseNumber: practitioner?.licenseNumber ?? '',
      // The native picker only takes lower case.
      agendaColor: (practitioner?.agendaColor ?? DEFAULT_COLOR).toLowerCase(),
      userId: practitioner?.userId ?? null,
    });
    // Once linked, the user stays (the backend answers 409 otherwise).
    if (practitioner?.userId) {
      this.form.controls.userId.disable();
    } else {
      this.form.controls.userId.enable();
    }
  }

  /** The first invalid field or, when only the licence as a whole is wrong, its missing field. */
  private focusInvalid(): void {
    const host = this.host.nativeElement;
    if (Object.values(this.form.controls).some((control) => control.invalid)) {
      focusFirstInvalid(this.form, host);
      return;
    }
    const error = licenseError(this.form);
    if (!error) {
      return;
    }
    const missing =
      error === 'licenseComplete' && this.form.controls.licenseProvince.value !== null
        ? 'licenseNumber'
        : 'licenseProvince';
    host.querySelector<HTMLElement>(`[formcontrolname="${missing}"]`)?.focus();
  }

  /**
   * A value that follows the events (value, status, touched…) of the form and its fields, as a
   * signal: `markAllAsTouched` marks each field on its own, without an event of the form.
   */
  private watch<T>(read: () => T): Signal<T> {
    const controls = Object.values(this.form.controls);
    return toSignal(merge(this.form.events, ...controls.map((c) => c.events)).pipe(map(read)), {
      initialValue: read(),
    });
  }

  /** Every page of the clinic's users, so all who may be linked are offered. */
  private async loadUsers(): Promise<void> {
    try {
      const users: ClinicUserDetails[] = [];
      for (let page = 0; ; page++) {
        const result = await this.api.invoke(list3, {
          pageable: { page, size: USERS_PAGE_SIZE },
        });
        users.push(...result.content);
        if (page + 1 >= result.page.totalPages) {
          break;
        }
      }
      this.users.set(users);
    } catch {
      this.usersFailed.set(true);
    }
  }
}
