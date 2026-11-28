import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { changeRole } from '../../api/fn/clinic-users-controller/change-role';
import { deactivate } from '../../api/fn/clinic-users-controller/deactivate';
import { list3 } from '../../api/fn/clinic-users-controller/list-3';
import { resendInvitation } from '../../api/fn/clinic-users-controller/resend-invitation';
import { ClinicUserDetails } from '../../api/models/clinic-user-details';
import { PagedModelClinicUserDetails } from '../../api/models/paged-model-clinic-user-details';
import { Session } from '../../core/auth/session';
import { CLINIC_ROLES, ClinicRole } from '../../core/clinic/active-clinic';
import { confirm } from '../../shared/dialogs/confirm-dialog';
import { FailureMessage } from '../../shared/forms/failure-message';
import { SaveFailure, toActionFailure } from '../../shared/forms/save-failure';
import { providePaginatorIntl } from '../../shared/lists/paginator-intl';
import { displayName, userStatus } from './clinic-user-names';
import { InviteUserForm } from './invite-user-form';

/** People per page of the list (the backend accepts up to 50). */
export const USERS_PAGE_SIZE = 20;

/** What the status region announces after an action on a row. */
interface Notice {
  readonly key: 'resent' | 'roleChanged' | 'deactivated';
  readonly params: Record<string, string>;
}

/**
 * «Usuarios y roles» of the configuration area, for ADMIN_CLINICA (M02-T05, M02-T08): the people
 * of the active clinic, paged in the order they joined, the invitation of new ones, and per person
 * resending a pending invitation, changing the role and deactivating the access. Role changes and
 * deactivations carry the version the user saw. The backend enforces every rule (nobody changes
 * their own role or deactivates themselves, a clinic always keeps an ADMIN_CLINICA); hiding those
 * actions on the user's own row is only a convenience.
 */
@Component({
  selector: 'app-clinic-users-home',
  imports: [
    FailureMessage,
    InviteUserForm,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    TranslocoPipe,
  ],
  providers: [providePaginatorIntl()],
  templateUrl: './clinic-users-home.html',
  styleUrl: './clinic-users-home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicUsersHome implements OnInit {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly session = inject(Session);
  private readonly transloco = inject(TranslocoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly pageSize = USERS_PAGE_SIZE;
  protected readonly roles = CLINIC_ROLES;
  protected readonly status = userStatus;
  protected readonly name = displayName;

  protected readonly page = signal<PagedModelClinicUserDetails | null>(null);
  protected readonly pageIndex = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  /** The membership an action is running on; the other actions wait for it. */
  protected readonly busy = signal<string | null>(null);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  /** The role chosen in each row's select, until it is applied. */
  private readonly chosen = signal<Readonly<Record<string, ClinicRole>>>({});

  /** The Keycloak `sub` of the signed-in user, which is the `userId` of their own row. */
  private readonly currentUserId = this.session.userId;

  ngOnInit(): void {
    void this.load(0);
  }

  protected isSelf(user: ClinicUserDetails): boolean {
    const id = this.currentUserId();
    return id !== null && user.userId === id;
  }

  protected choice(user: ClinicUserDetails): ClinicRole | undefined {
    return this.chosen()[user.id ?? ''] ?? user.role;
  }

  protected choose(user: ClinicUserDetails, role: ClinicRole): void {
    this.chosen.update((chosen) => ({ ...chosen, [user.id ?? '']: role }));
  }

  // The backend's default order is the creation order; no sort is sent (see issue #160).
  protected async load(pageIndex: number): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const page = await this.api.invoke(list3, {
        pageable: { page: pageIndex, size: USERS_PAGE_SIZE },
      });
      this.page.set(page);
      this.pageIndex.set(pageIndex);
      this.chosen.set({});
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected changePage(event: PageEvent): Promise<void> {
    this.notice.set(null);
    this.failure.set(null);
    return this.load(event.pageIndex);
  }

  /** A new invitation: the list is read again, since the person may now be on this page. */
  protected invited(): Promise<void> {
    this.notice.set(null);
    this.failure.set(null);
    return this.load(this.pageIndex());
  }

  protected resend(user: ClinicUserDetails): Promise<void> {
    return this.act(user, async () => {
      await this.api.invoke(resendInvitation, { id: user.id ?? '' });
      return { key: 'resent', params: { name: displayName(user) } };
    });
  }

  protected applyRole(user: ClinicUserDetails): Promise<void> {
    const role = this.choice(user);
    if (!role || role === user.role) {
      return Promise.resolve();
    }
    return this.act(user, async () => {
      const changed = await this.api.invoke(changeRole, {
        id: user.id ?? '',
        body: { role, version: user.version ?? 0 },
      });
      this.replace(changed);
      return {
        key: 'roleChanged',
        params: {
          name: displayName(changed),
          role: this.transloco.translate(`roles.${changed.role ?? role}`),
        },
      };
    });
  }

  protected async deactivateUser(user: ClinicUserDetails): Promise<void> {
    this.notice.set(null);
    const confirmed = await confirm(this.dialog, {
      titleKey: 'clinicUsers.deactivateConfirm.title',
      messageKey: 'clinicUsers.deactivateConfirm.message',
      messageParams: { name: displayName(user) },
      confirmKey: 'clinicUsers.deactivateConfirm.confirm',
    });
    if (!confirmed) {
      return;
    }
    await this.act(user, async () => {
      const changed = await this.api.invoke(deactivate, {
        id: user.id ?? '',
        body: { version: user.version ?? 0 },
      });
      this.replace(changed);
      // Its button is gone: the focus goes to the row, instead of being lost.
      this.focusRow(changed.id ?? '');
      return { key: 'deactivated', params: { name: displayName(changed) } };
    });
  }

  private async act(user: ClinicUserDetails, action: () => Promise<Notice>): Promise<void> {
    this.busy.set(user.id ?? '');
    this.notice.set(null);
    this.failure.set(null);
    try {
      this.notice.set(await action());
    } catch (error) {
      this.failure.set(toActionFailure(error));
    } finally {
      this.busy.set(null);
    }
  }

  /** The membership as the backend returned it after a change, with its new version. */
  private replace(user: ClinicUserDetails): void {
    this.page.update(
      (page) =>
        page && {
          ...page,
          content: page.content.map((row) => (row.id === user.id ? user : row)),
        },
    );
    this.chosen.update((chosen) => {
      const rest = { ...chosen };
      delete rest[user.id ?? ''];
      return rest;
    });
  }

  private focusRow(id: string): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(`[data-user="${id}"] > th`)?.focus(),
      { injector: this.injector },
    );
  }
}
