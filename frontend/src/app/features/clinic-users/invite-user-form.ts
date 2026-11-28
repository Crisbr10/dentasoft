import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { invite1 } from '../../api/fn/clinic-users-controller/invite-1';
import { CLINIC_ROLES, ClinicRole } from '../../core/clinic/active-clinic';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';

/** First and last name, as long as the backend accepts them. */
const PERSON_NAME_MAX = 100;

/**
 * Invites a person to the active clinic with a role (POST /api/v1/clinic/users/invitations,
 * M02-T05). Inviting again someone whose access was deactivated gives it back. The backend
 * answers 202 with no body; a refusal (already a member, too many invitations today…) comes as
 * its ProblemDetail.
 */
@Component({
  selector: 'app-invite-user-form',
  imports: [
    ControlError,
    FormActions,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="invite-user-title">{{ 'clinicUsers.invite.title' | transloco }}</h3>
    <p>{{ 'clinicUsers.invite.intro' | transloco }}</p>
    <form class="invite-user" [attr.aria-labelledby]="'invite-user-title'" [formGroup]="form">
      <mat-form-field>
        <mat-label>{{ 'clinicUsers.invite.email' | transloco }}</mat-label>
        <input
          matInput
          type="email"
          formControlName="email"
          autocomplete="off"
          spellcheck="false"
          [attr.autocapitalize]="'none'"
          required
        />
        <mat-error><app-control-error [control]="form.controls.email" /></mat-error>
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ 'clinicUsers.invite.firstName' | transloco }}</mat-label>
        <input matInput formControlName="firstName" autocomplete="off" required />
        <mat-error><app-control-error [control]="form.controls.firstName" /></mat-error>
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ 'clinicUsers.invite.lastName' | transloco }}</mat-label>
        <input matInput formControlName="lastName" autocomplete="off" required />
        <mat-error><app-control-error [control]="form.controls.lastName" /></mat-error>
      </mat-form-field>
      <fieldset class="invite-user__roles">
        <legend>{{ 'clinicUsers.invite.role' | transloco }}</legend>
        <mat-radio-group
          formControlName="role"
          class="invite-user__role-options"
          [attr.aria-describedby]="roleInvalid() ? 'invite-user-role-error' : null"
        >
          @for (role of roles; track role) {
            <mat-radio-button [value]="role">{{ 'roles.' + role | transloco }}</mat-radio-button>
          }
        </mat-radio-group>
        <p id="invite-user-role-error" class="invite-user__error">
          @if (roleInvalid()) {
            <app-control-error [control]="form.controls.role" />
          }
        </p>
      </fieldset>
      <app-form-actions
        [form]="form"
        [saving]="saving()"
        [failure]="failure()"
        [saveKey]="'clinicUsers.invite.send'"
        (save)="send()"
        (discard)="discard()"
      />
    </form>
    <p class="invite-user__notice" role="status">
      @if (invited(); as email) {
        {{ 'clinicUsers.invite.invited' | transloco: { email } }}
      }
    </p>
  `,
  styles: `
    .invite-user {
      display: flex;
      flex-direction: column;
      max-inline-size: 32rem;
    }

    .invite-user__roles {
      margin: 0 0 8px;
      padding: 0;
      border: none;

      legend {
        font: var(--mat-sys-body-large);
        padding: 0;
      }
    }

    .invite-user__role-options {
      display: flex;
      flex-direction: column;
    }

    .invite-user__error {
      margin: 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-small);
    }

    .invite-user__notice:empty {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InviteUserForm {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** An invitation was sent: the list may have a new row. */
  readonly sent = output<void>();

  protected readonly roles = CLINIC_ROLES;
  protected readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email],
    }),
    firstName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(PERSON_NAME_MAX)],
    }),
    lastName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(PERSON_NAME_MAX)],
    }),
    role: new FormControl<ClinicRole | null>(null, { validators: [Validators.required] }),
  });
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  /** The email of the last invitation sent, for the notice. */
  protected readonly invited = signal<string | null>(null);
  /** The radio group has no `<mat-error>` of its own: its message shows after a submit. */
  protected readonly roleInvalid = signal(false);

  constructor() {
    this.form.controls.role.valueChanges.subscribe(() =>
      this.roleInvalid.set(this.form.controls.role.invalid),
    );
  }

  protected async send(): Promise<void> {
    this.invited.set(null);
    this.form.markAllAsTouched();
    this.roleInvalid.set(this.form.controls.role.invalid);
    const { email, firstName, lastName, role } = this.form.getRawValue();
    if (this.form.invalid || role === null) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    try {
      await this.api.invoke(invite1, { body: { email, firstName, lastName, role } });
      this.form.reset();
      this.roleInvalid.set(false);
      this.invited.set(email);
      this.sent.emit();
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      this.roleInvalid.set(this.form.controls.role.invalid);
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.form.reset();
    this.failure.set(null);
    this.roleInvalid.set(false);
  }
}
