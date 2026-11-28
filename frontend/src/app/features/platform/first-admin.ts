import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { cancel } from '../../api/fn/first-admin-invitation-controller/cancel';
import { invite } from '../../api/fn/first-admin-invitation-controller/invite';
import { resend } from '../../api/fn/first-admin-invitation-controller/resend';
import { status } from '../../api/fn/first-admin-invitation-controller/status';
import { AdminInvitationStatus } from '../../api/models/admin-invitation-status';
import { confirm } from '../../shared/dialogs/confirm-dialog';
import { FailureMessage } from '../../shared/forms/failure-message';
import { FormActions } from '../../shared/forms/form-actions';
import { SaveFailure, toActionFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { ControlError } from './control-error';
import { focusFirstInvalid } from './form-focus';
import { withParsedProblem } from './text-problem';

/** First and last name, as long as the backend accepts them. */
const PERSON_NAME_MAX = 100;

type Notice = 'invited' | 'resent' | 'cancelled';

/**
 * The first administrator of a clinic (M02-T11): whether someone was invited and when, never who.
 * With no invitation it invites someone; a pending one can be sent again or cancelled; once
 * accepted, the clinic's own administrator invites the rest of the team.
 */
@Component({
  selector: 'app-first-admin',
  imports: [
    ControlError,
    DatePipe,
    FailureMessage,
    FormActions,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h4 id="first-admin-title">{{ 'platform.admin.title' | transloco }}</h4>
    @if (invitation(); as invitation) {
      @switch (invitation.status) {
        @case ('PENDING') {
          <p class="first-admin__state">
            {{
              'platform.admin.pending' | transloco: { date: invitation.invitedAt | date: 'medium' }
            }}
          </p>
          <div class="first-admin__actions">
            <button
              mat-stroked-button
              type="button"
              [disabled]="busy()"
              (click)="resendInvitation()"
            >
              {{ 'platform.admin.resend' | transloco }}
            </button>
            <button
              mat-stroked-button
              type="button"
              [disabled]="busy()"
              (click)="cancelInvitation()"
            >
              {{ 'platform.admin.cancel' | transloco }}
            </button>
          </div>
        }
        @case ('ACCEPTED') {
          <p class="first-admin__state">
            {{
              'platform.admin.accepted'
                | transloco: { date: invitation.acceptedAt | date: 'medium' }
            }}
          </p>
        }
        @default {
          <p class="first-admin__state">{{ 'platform.admin.none' | transloco }}</p>
          <form
            class="platform-form"
            [attr.aria-labelledby]="'first-admin-title'"
            [formGroup]="form"
          >
            <mat-form-field>
              <mat-label>{{ 'platform.fields.email' | transloco }}</mat-label>
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
              <mat-label>{{ 'platform.fields.firstName' | transloco }}</mat-label>
              <input matInput formControlName="firstName" autocomplete="off" required />
              <mat-error><app-control-error [control]="form.controls.firstName" /></mat-error>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'platform.fields.lastName' | transloco }}</mat-label>
              <input matInput formControlName="lastName" autocomplete="off" required />
              <mat-error><app-control-error [control]="form.controls.lastName" /></mat-error>
            </mat-form-field>
            <app-form-actions
              [form]="form"
              [saving]="saving()"
              [failure]="failure()"
              (save)="sendInvitation()"
              (discard)="discard()"
            />
          </form>
        }
      }
    } @else if (loadFailure(); as loadFailure) {
      <app-failure-message [failure]="loadFailure" />
      <button mat-stroked-button type="button" (click)="reload()">
        <mat-icon svgIcon="refresh" />
        {{ 'platform.admin.retry' | transloco }}
      </button>
    } @else {
      <p class="first-admin__state">
        <mat-spinner diameter="20" aria-hidden="true" />
        <span>{{ 'platform.admin.loading' | transloco }}</span>
      </p>
    }
    <app-failure-message [failure]="actionFailure()" />
    <p class="platform-notice" role="status">
      @if (notice(); as notice) {
        {{ 'platform.admin.' + notice | transloco }}
      }
    </p>
  `,
  styleUrl: './platform-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FirstAdmin {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly clinicId = input.required<string>();

  protected readonly invitation = signal<AdminInvitationStatus | null>(null);
  protected readonly loadFailure = signal<SaveFailure | null>(null);
  protected readonly busy = signal(false);
  protected readonly actionFailure = signal<SaveFailure | null>(null);
  protected readonly notice = signal<Notice | null>(null);

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
  });
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);

  constructor() {
    effect(() => {
      const id = this.clinicId();
      untracked(() => {
        this.invitation.set(null);
        this.form.reset();
        this.failure.set(null);
        this.actionFailure.set(null);
        this.notice.set(null);
        void this.load(id);
      });
    });
  }

  protected reload(): Promise<void> {
    return this.load(this.clinicId());
  }

  protected async sendInvitation(): Promise<void> {
    this.notice.set(null);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    const id = this.clinicId();
    this.saving.set(true);
    this.failure.set(null);
    try {
      await this.api.invoke(invite, { id, body: this.form.getRawValue() });
      this.form.reset();
      this.notice.set('invited');
      await this.load(id);
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, withParsedProblem(error)));
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.form.reset();
    this.failure.set(null);
  }

  protected resendInvitation(): Promise<void> {
    return this.act(() => this.api.invoke(resend, { id: this.clinicId() }), 'resent');
  }

  protected async cancelInvitation(): Promise<void> {
    this.notice.set(null);
    const confirmed = await confirm(this.dialog, {
      titleKey: 'platform.admin.cancelConfirm.title',
      messageKey: 'platform.admin.cancelConfirm.message',
      confirmKey: 'platform.admin.cancelConfirm.confirm',
    });
    if (confirmed) {
      await this.act(() => this.api.invoke(cancel, { id: this.clinicId() }), 'cancelled');
    }
  }

  /** Resends or cancels, then reads the invitation again. */
  private async act(action: () => Promise<void>, notice: Notice): Promise<void> {
    const id = this.clinicId();
    this.busy.set(true);
    this.notice.set(null);
    this.actionFailure.set(null);
    try {
      await action();
      this.notice.set(notice);
      await this.load(id);
    } catch (error) {
      this.actionFailure.set(toActionFailure(withParsedProblem(error)));
    } finally {
      this.busy.set(false);
    }
  }

  /** Reads the invitation of a clinic; an answer for a clinic no longer shown is ignored. */
  private async load(id: string): Promise<void> {
    this.loadFailure.set(null);
    try {
      const invitation = await this.api.invoke(status, { id });
      if (id === this.clinicId()) {
        this.invitation.set(invitation);
      }
    } catch (error) {
      if (id === this.clinicId()) {
        this.invitation.set(null);
        this.loadFailure.set(toActionFailure(error));
      }
    }
  }
}
